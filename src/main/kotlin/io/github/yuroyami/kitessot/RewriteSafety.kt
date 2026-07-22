package io.github.yuroyami.kitessot

import org.gradle.api.logging.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView

/** Suffix appended to a user-owned file before kiteSsot rewrites it. */
internal const val BACKUP_SUFFIX = ".kitessot.bak"
internal const val MAX_REWRITE_TEXT_BYTES = 64L * 1024L * 1024L
private const val MAX_DIFF_CHANGED_LINES_PER_SIDE = 40
private const val MAX_DIFF_LINE_CHARS = 512
private const val MAX_DIFF_PATH_CHARS = 1_024

internal data class PlannedTextChange(
    val file: File,
    val originalBytes: ByteArray?,
    val originalText: String?,
    val newText: String,
    val label: String,
)

/** One strict UTF-8 read used both to derive and later validate a rewrite. */
internal data class Utf8FileSnapshot(
    val file: File,
    val bytes: ByteArray,
    val text: String,
)

internal data class RewriteApplyResult(val planned: Int, val written: Int, val dryRun: Boolean)

/** Decode UTF-8 without silently replacing malformed bytes or accepting a BOM. */
internal fun readUtf8SnapshotStrict(file: File): Utf8FileSnapshot {
    val bytes = readRegularFileBoundedStable(
        file.toPath(),
        MAX_REWRITE_TEXT_BYTES,
        "in-place text migration input",
    )
    require(!(bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
        (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())))) {
        "${file.path} is UTF-16; the in-place migration supports UTF-8 text only"
    }
    val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    require(offset == 0) { "${file.path} has a UTF-8 BOM; refusing a rewrite that would change its encoding marker" }
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return Utf8FileSnapshot(file.absoluteFile.normalize(), bytes, text)
}

internal fun readUtf8Strict(file: File): String = readUtf8SnapshotStrict(file).text

/**
 * Resolve [candidate] inside [root], rejecting `..` escapes and every symlink in
 * the existing path. The returned file is normalized but not symlink-resolved.
 */
internal fun requireContainedPath(
    root: File,
    candidate: File,
    mustExist: Boolean = true,
    requireRegularFile: Boolean = true,
): File {
    val rootPath = root.toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) { "rewrite root is not a directory: $rootPath" }
    require(!Files.isSymbolicLink(rootPath)) { "rewrite root may not be a symlink: $rootPath" }
    val path = candidate.toPath().toAbsolutePath().normalize()
    require(path.startsWith(rootPath)) { "path escapes the allowed rewrite root $rootPath: $path" }

    var cursor = rootPath
    val relative = rootPath.relativize(path)
    relative.forEach { part ->
        cursor = cursor.resolve(part)
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(cursor)) { "symlink traversal is not allowed for rewrites: $cursor" }
        }
    }
    if (mustExist) require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "rewrite target does not exist: $path" }
    if (mustExist && requireRegularFile) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "rewrite target is not a regular file: $path" }
    }

    val rootReal = rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val existing = generateSequence(path) { it.parent }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        ?: error("no existing ancestor for $path")
    require(existing.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(rootReal)) {
        "resolved path escapes the allowed rewrite root $rootReal: $path"
    }
    return path.toFile()
}

internal fun planTextChange(
    root: File,
    file: File,
    content: String,
    label: String,
    snapshot: Utf8FileSnapshot? = null,
): PlannedTextChange? {
    val exists = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    val safe = requireContainedPath(root, file, mustExist = snapshot != null || exists, requireRegularFile = true)
    val original = snapshot?.also {
        require(it.file.toPath().toAbsolutePath().normalize() == safe.toPath().toAbsolutePath().normalize()) {
            "rewrite snapshot belongs to ${it.file.path}, not ${safe.path}"
        }
    } ?: if (Files.exists(safe.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        readUtf8SnapshotStrict(safe)
    } else {
        null
    }
    if (original?.text == content) return null
    return PlannedTextChange(safe, original?.bytes, original?.text, content, label)
}

/**
 * Apply a batch under one process lock. Every target is staged and revalidated
 * immediately before its move. If a later move fails, rollback first moves the
 * current target aside, verifies the moved bytes, and restores with a create-only
 * move. A newer replacement or recreation at the target path is therefore never
 * overwritten. Portable filesystems provide no content-aware compare-and-swap,
 * so a non-cooperating writer that retains an already-open file handle remains
 * outside this process-lock transaction.
 */
internal fun applyTextRewritePlan(
    root: File,
    changes: List<PlannedTextChange>,
    backup: Boolean,
    dryRun: Boolean,
    logger: Logger,
    beforeMutation: (index: Int, target: Path) -> Unit = { _, _ -> },
): RewriteApplyResult {
    if (changes.isEmpty()) return RewriteApplyResult(0, 0, dryRun)
    val safeRoot = requireContainedPath(root, root, mustExist = true, requireRegularFile = false)
    val normalized = changes.map { change ->
        val safe = requireContainedPath(safeRoot, change.file, mustExist = change.originalBytes != null)
        change.copy(file = safe)
    }
    require(normalized.map { it.file.toPath() }.distinct().size == normalized.size) { "rewrite plan contains duplicate target paths" }

    if (dryRun) {
        normalized.forEach { change ->
            logger.lifecycle(renderUnifiedDiff(change.file, change.originalText.orEmpty(), change.newText))
        }
        return RewriteApplyResult(normalized.size, 0, true)
    }

    // Keep coordination state in Gradle's ignored project metadata rather than
    // leaving an untracked lock file beside user source. The file remains stable
    // across invocations, which avoids the split-lock race caused by deleting a
    // lock inode while another process is waiting on it.
    val lockDirectory = safeRoot.toPath().resolve(".gradle/kitessot")
    requireContainedPath(safeRoot, lockDirectory.toFile(), mustExist = false, requireRegularFile = false)
    Files.createDirectories(lockDirectory)
    requireContainedPath(safeRoot, lockDirectory.toFile(), mustExist = true, requireRegularFile = false)
    val lockPath = lockDirectory.resolve("rewrite.lock")
    require(!Files.isSymbolicLink(lockPath)) { "rewrite lock may not be a symlink: $lockPath" }
    FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use {
            normalized.forEach { verifyUnchanged(it) }
            val staged = mutableListOf<Triple<PlannedTextChange, Path, ByteArray>>()
            try {
                normalized.forEach { change ->
                    val writtenBytes = change.newText.toByteArray(StandardCharsets.UTF_8)
                    staged += Triple(change, stageSibling(change.file.toPath(), writtenBytes), writtenBytes)
                }
                if (backup) {
                    normalized.forEach { change ->
                        change.originalBytes?.let { original -> backupAtomically(change.file.toPath(), original) }
                    }
                }
                val attempted = mutableListOf<Pair<PlannedTextChange, ByteArray>>()
                try {
                    staged.forEachIndexed { index, (change, temp, writtenBytes) ->
                        beforeMutation(index, change.file.toPath())
                        requireContainedPath(
                            safeRoot,
                            change.file,
                            mustExist = change.originalBytes != null,
                            requireRegularFile = true,
                        )
                        verifyUnchanged(change)
                        attempted += change to writtenBytes
                        installTextChangeIfSnapshotUnchanged(change.file.toPath(), temp, change.originalBytes)
                    }
                } catch (failure: Exception) {
                    attempted.asReversed().forEach { (change, writtenBytes) ->
                        runCatching {
                            restoreTextChangeIfStillWritten(change.file.toPath(), writtenBytes, change.originalBytes)
                        }.exceptionOrNull()?.let(failure::addSuppressed)
                    }
                    throw failure
                }
            } finally {
                staged.forEach { (_, temp, _) -> Files.deleteIfExists(temp) }
            }
        }
    }
    return RewriteApplyResult(normalized.size, normalized.size, false)
}

private fun verifyUnchanged(change: PlannedTextChange) {
    val path = change.file.toPath()
    val expected = change.originalBytes
    val same = if (expected == null) {
        !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    } else {
        currentBytesEqual(path, expected)
    }
    check(same) { "${change.file.path} changed after the rewrite plan was computed; no changes were applied" }
}

private fun stageSibling(target: Path, bytes: ByteArray): Path {
    Files.createDirectories(target.parent)
    val tmp = Files.createTempFile(target.parent, ".${target.fileName}.kitessot-", ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.getFileAttributeView(target, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.let { view ->
                runCatching { Files.setPosixFilePermissions(tmp, view.readAttributes().permissions()) }
            }
        }
        return tmp
    } catch (failure: Exception) {
        Files.deleteIfExists(tmp)
        throw failure
    }
}

private fun currentBytesEqual(path: Path, expected: ByteArray): Boolean {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
    var offset = 0
    var equal = true
    return runCatching {
        consumeRegularFileBoundedStable(path, expected.size.toLong(), "rewrite transaction target") {
                buffer, start, count ->
            if (offset + count > expected.size) {
                equal = false
            } else if (equal) {
                repeat(count) { index ->
                    if (buffer[start + index] != expected[offset + index]) equal = false
                }
            }
            offset += count
        }
        equal && offset == expected.size
    }.getOrDefault(false)
}

/**
 * Commit one staged rewrite without a compare-then-replace gap. Existing content
 * is parked and verified before the staged file is installed create-only.
 */
private fun installTextChangeIfSnapshotUnchanged(path: Path, staged: Path, expectedBytes: ByteArray?) {
    if (expectedBytes == null) {
        check(moveNewWithoutReplacing(staged, path)) {
            "$path appeared after the rewrite plan was computed; it was not overwritten"
        }
        return
    }

    val parkedCurrent = moveCurrentPathAside(path)
        ?: error("$path disappeared after the rewrite plan was computed; it was not recreated")
    var preserveParkedCurrent = false
    try {
        if (!currentBytesEqual(parkedCurrent, expectedBytes)) {
            if (!moveNewWithoutReplacing(parkedCurrent, path)) {
                preserveParkedCurrent = true
                error(
                    "$path changed while the rewrite was being committed; newer entries were preserved at " +
                        "$path and $parkedCurrent",
                )
            }
            error("$path changed while the rewrite was being committed; it was restored and not overwritten")
        }
        if (!moveNewWithoutReplacing(staged, path)) {
            preserveParkedCurrent = true
            error(
                "$path was recreated while the rewrite was being committed; the newer entry was preserved " +
                    "and the planned original is available at $parkedCurrent",
            )
        }
    } finally {
        if (!preserveParkedCurrent) Files.deleteIfExists(parkedCurrent)
    }
}

private fun restoreTextChangeIfStillWritten(path: Path, writtenBytes: ByteArray, originalBytes: ByteArray?) {
    val stagedOriginal = originalBytes?.let { stageSibling(path, it) }
    val parkedCurrent = moveCurrentPathAside(path) ?: run {
        stagedOriginal?.let(Files::deleteIfExists)
        return
    }
    var preserveParkedCurrent = false
    var preserveStagedOriginal = false
    try {
        if (!currentBytesEqual(parkedCurrent, writtenBytes)) {
            if (!moveNewWithoutReplacing(parkedCurrent, path)) {
                preserveParkedCurrent = true
                throw IllegalStateException(
                    "rollback found newer content and preserved its displaced copy at $parkedCurrent; " +
                        "another newer entry also exists at $path",
                )
            }
            return
        }

        // The parked entry is exactly this transaction's output. Removing it is
        // safe; restore the prior snapshot only if the target is still absent.
        if (stagedOriginal != null && !moveNewWithoutReplacing(stagedOriginal, path)) {
            preserveStagedOriginal = true
            throw IllegalStateException(
                "rollback preserved a newer entry at $path; the prior snapshot is available at $stagedOriginal",
            )
        }
    } finally {
        if (!preserveParkedCurrent) Files.deleteIfExists(parkedCurrent)
        if (stagedOriginal != null && !preserveStagedOriginal) Files.deleteIfExists(stagedOriginal)
    }
}

/**
 * Move whichever entry currently occupies [path] to a unique sibling without
 * replacing anything. Verification happens against the moved inode, eliminating
 * the compare-then-REPLACE race at the public target path.
 */
private fun moveCurrentPathAside(path: Path): Path? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    val parked = Files.createTempFile(path.parent, ".${path.fileName}.kitessot-current-", ".tmp")
    Files.delete(parked)
    return try {
        Files.move(path, parked)
        forceDirectory(path.parent)
        parked
    } catch (_: NoSuchFileException) {
        Files.deleteIfExists(parked)
        null
    } catch (failure: Exception) {
        Files.deleteIfExists(parked)
        throw failure
    }
}

/** Create-only sibling move. Unlike `ATOMIC_MOVE`, this has specified no-replace semantics. */
private fun moveNewWithoutReplacing(source: Path, target: Path): Boolean = try {
    Files.move(source, target)
    forceDirectory(target.parent)
    true
} catch (failure: Exception) {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) false else throw failure
}

private fun backupAtomically(target: Path, originalBytes: ByteArray) {
    val backup = target.resolveSibling(target.fileName.toString() + BACKUP_SUFFIX)
    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(backup)) {
            "backup path is not a regular file: $backup"
        }
        return
    }
    val tmp = Files.createTempFile(target.parent, ".${target.fileName}.backup-", ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(originalBytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            // Keep write-once semantics even under a concurrent creator.
            // ATOMIC_MOVE may replace an existing target implementation-specifically.
            Files.move(tmp, backup)
        } catch (_: FileAlreadyExistsException) {
            // Another cooperating writer won the write-once backup race.
        }
        forceDirectory(target.parent)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

private fun forceDirectory(directory: Path) {
    runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
}

internal fun renderUnifiedDiff(file: File, old: String, new: String): String {
    val oldLines = old.split('\n')
    val newLines = new.split('\n')
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
        oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
    ) suffix++
    val contextStart = (prefix - 3).coerceAtLeast(0)
    val oldEnd = (oldLines.size - suffix + 3).coerceAtMost(oldLines.size)
    val newEnd = (newLines.size - suffix + 3).coerceAtMost(newLines.size)
    return buildString {
        val safePath = diagnosticSafeText(file.path, MAX_DIFF_PATH_CHARS)
        appendLine("--- $safePath")
        appendLine("+++ $safePath (kiteSsot plan)")
        appendLine("@@ -${contextStart + 1},${oldEnd - contextStart} +${contextStart + 1},${newEnd - contextStart} @@")
        fun appendSafeLine(marker: Char, line: String) {
            append(marker).appendLine(diagnosticSafeText(line, MAX_DIFF_LINE_CHARS))
        }
        oldLines.subList(contextStart, prefix).forEach { appendSafeLine(' ', it) }
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        removed.take(MAX_DIFF_CHANGED_LINES_PER_SIDE).forEach { appendSafeLine('-', it) }
        if (removed.size > MAX_DIFF_CHANGED_LINES_PER_SIDE) {
            appendLine("-… ${removed.size - MAX_DIFF_CHANGED_LINES_PER_SIDE} removed line(s) omitted …")
        }
        val added = newLines.subList(prefix, newLines.size - suffix)
        added.take(MAX_DIFF_CHANGED_LINES_PER_SIDE).forEach { appendSafeLine('+', it) }
        if (added.size > MAX_DIFF_CHANGED_LINES_PER_SIDE) {
            appendLine("+… ${added.size - MAX_DIFF_CHANGED_LINES_PER_SIDE} added line(s) omitted …")
        }
        val sharedSuffix = minOf(oldEnd - (oldLines.size - suffix), newEnd - (newLines.size - suffix))
        repeat(sharedSuffix.coerceAtLeast(0)) { offset ->
            appendSafeLine(' ', oldLines[oldLines.size - suffix + offset])
        }
    }.trimEnd()
}

/** Backward-compatible single-file wrapper used by generated asset installers. */
internal fun writeTextSafely(
    file: File,
    content: String,
    backup: Boolean,
    dryRun: Boolean,
    logger: Logger,
    label: String,
): Boolean {
    if (Files.isSymbolicLink(file.toPath())) error("refusing to read or replace symlink: ${file.path}")
    val exists = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    val snapshot = if (exists) readUtf8SnapshotStrict(file) else null
    if (snapshot?.text == content) return false
    if (dryRun) {
        logger.lifecycle(renderUnifiedDiff(file, snapshot?.text.orEmpty(), content))
        return false
    }
    if (backup) snapshot?.bytes?.let { backupAtomically(file.toPath(), it) }
    val staged = stageSibling(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    try {
        installTextChangeIfSnapshotUnchanged(file.toPath(), staged, snapshot?.bytes)
    } finally {
        Files.deleteIfExists(staged)
    }
    return true
}

/** Byte-oriented sibling of [writeTextSafely] for generated images. */
internal fun writeBytesSafely(
    file: File,
    bytes: ByteArray,
    dryRun: Boolean,
    logger: Logger,
    label: String,
): Boolean {
    if (Files.isSymbolicLink(file.toPath())) error("refusing to read or replace symlink: ${file.path}")
    val snapshot = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        readRegularFileBoundedStable(file.toPath(), MAX_REWRITE_TEXT_BYTES, "byte output target")
    } else {
        null
    }
    if (snapshot?.contentEquals(bytes) == true) return false
    if (dryRun) {
        logger.lifecycle("[kiteSsot][dry-run] would write $label (${file.path})")
        return false
    }
    val staged = stageSibling(file.toPath(), bytes)
    try {
        installTextChangeIfSnapshotUnchanged(file.toPath(), staged, snapshot)
    } finally {
        Files.deleteIfExists(staged)
    }
    return true
}

/** Reads one regular-file snapshot with a hard streaming bound and stability check. */
private fun readRegularFileBoundedStable(path: Path, maximumBytes: Long, label: String): ByteArray {
    require(maximumBytes in 0..Int.MAX_VALUE.toLong()) { "maximumBytes must fit in one byte array" }
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
    consumeRegularFileBoundedStable(path, maximumBytes, label, output::write)
    return output.toByteArray()
}

private inline fun consumeRegularFileBoundedStable(
    path: Path,
    maximumBytes: Long,
    label: String,
    consume: (ByteArray, Int, Int) -> Unit,
) {
    require(maximumBytes >= 0) { "maximumBytes must not be negative" }
    val before = readStableAttributes(path, label)
    require(before.isRegularFile && before.size() <= maximumBytes) {
        "$label is too large or unsafe: $path (limit $maximumBytes bytes)"
    }
    var total = 0L
    Files.newByteChannel(
        path,
        setOf<java.nio.file.OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ).use { channel ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val byteBuffer = ByteBuffer.wrap(buffer)
        while (true) {
            byteBuffer.clear()
            val count = channel.read(byteBuffer)
            if (count < 0) break
            if (count == 0) continue
            require(count.toLong() <= maximumBytes - total) {
                "$label grew beyond $maximumBytes bytes while being read: $path"
            }
            consume(buffer, 0, count)
            total += count
        }
    }
    val after = readStableAttributes(path, label)
    require(
        total == before.size() &&
            after.size() == before.size() &&
            after.fileKey() == before.fileKey() &&
            after.lastModifiedTime() == before.lastModifiedTime() &&
            after.creationTime() == before.creationTime(),
    ) {
        "$label changed while being read: $path"
    }
}

private fun readStableAttributes(path: Path, label: String): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also { attributes ->
        require(attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther) {
            "$label is not a regular no-follow file: $path"
        }
    }
