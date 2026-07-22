package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Provenance-aware file installation for generated sources and source-tree assets.
 *
 * A previous file is removed only when a manifest says this plugin owns it and its
 * current checksum still matches that manifest. All traversal is no-follow and every
 * target is constrained to its declared root. This intentionally fails closed rather
 * than guessing that a familiar filename is generated.
 */
internal object OwnedOutputSafety {
    internal const val OWNERSHIP_MANIFEST_NAME = ".kmpssot-owned-files-v1"
    private const val OWNERSHIP_HEADER = "kmp-ssot-owned-files-v1"
    private const val REMOVAL_HEADER = "kmp-ssot-removal-provenance-v1"
    private const val MAX_MANIFEST_BYTES = 1024L * 1024L
    private const val MAX_MIGRATION_FILE_BYTES = 64L * 1024L * 1024L
    private const val MAX_TRANSACTION_SNAPSHOT_BYTES = 256L * 1024L * 1024L
    private const val MAX_OWNED_OUTPUT_ENTRIES = 10_000
    private const val MAX_OWNED_OUTPUT_DEPTH = 64

    private data class OwnedEntry(val relativePath: String, val sha256: String)
    private data class OwnershipSnapshot(
        val entries: List<OwnedEntry>,
        val bytes: ByteArray?,
        val inputFingerprint: String?,
    )
    private data class RemovalSetup(
        val root: Path,
        val backups: Path,
        val targets: List<Path>,
        val snapshots: Map<Path, ByteArray>,
    )
    private data class VerifiedRemovalBatch(
        val targets: List<Path>,
        val backups: Map<Path, Path>,
        val hashes: Map<Path, String>,
    )

    /** Read-only verification used by diagnostics; never repairs or claims files. */
    internal fun inspectInstalledFiles(
        installationRoot: File,
        manifestFile: File,
        projectRoot: File,
        owner: String,
        expectedRelativePaths: Collection<String>,
        expectedInputFingerprint: String? = null,
    ): List<String> = runCatching {
        requireProjectContainedPath(installationRoot, projectRoot, "owned output root")
        requireProjectContainedPath(manifestFile, projectRoot, "ownership manifest")
        requireSafePath(installationRoot, "owned output root")
        val root = installationRoot.toPath().absoluteNormalized()
        val manifest = manifestFile.toPath().absoluteNormalized()
        val snapshot = readOwnershipManifest(manifest, owner)
        if (snapshot.bytes == null) {
            return@runCatching listOf("ownership manifest is missing: $manifest")
        }
        val expected = expectedRelativePaths.map(::validateRelativePath).toSet()
        val actual = snapshot.entries.mapTo(linkedSetOf(), OwnedEntry::relativePath)
        buildList {
            if (expectedInputFingerprint != null && snapshot.inputFingerprint != expectedInputFingerprint) {
                add(
                    if (snapshot.inputFingerprint == null) {
                        "ownership manifest has no input/renderer fingerprint; rerun the installer"
                    } else {
                        "installed outputs were produced from different logo inputs or renderer policy"
                    },
                )
            }
            (expected - actual).sorted().forEach { add("expected owned output is absent from the manifest: $it") }
            (actual - expected).sorted().forEach { add("manifest contains stale or unexpected owned output: $it") }
            snapshot.entries.forEach { entry ->
                val target = root.resolve(entry.relativePath).normalize()
                requireContained(root, target, "owned output inspection")
                if (!Files.exists(target, NOFOLLOW_LINKS)) {
                    add("manifest-owned output is missing: ${entry.relativePath}")
                } else {
                    val attrs = safeAttributes(target, "manifest-owned output")
                    when {
                        !attrs.isRegularFile -> add("manifest-owned output is not a regular file: ${entry.relativePath}")
                        attrs.size() > MAX_MIGRATION_FILE_BYTES -> add("manifest-owned output is too large: ${entry.relativePath}")
                        sha256Bounded(target, MAX_MIGRATION_FILE_BYTES, "manifest-owned output") != entry.sha256 ->
                            add("manifest-owned output checksum differs: ${entry.relativePath}")
                    }
                }
            }
        }
    }.getOrElse { failure ->
        listOf(failure.message ?: "ownership inspection failed: ${failure::class.simpleName}")
    }

    /**
     * Atomically replaces an exclusive generated tree below [allowedRoot]. Unknown
     * content is never deleted: its presence aborts generation with recovery advice.
     */
    fun replaceGeneratedTree(
        outputRoot: File,
        allowedRoot: File,
        owner: String,
        files: Map<String, ByteArray>,
        beforeMutation: (index: Int, target: Path) -> Unit = { _, _ -> },
    ) {
        val lexicalRoot = outputRoot.toPath().toAbsolutePath().normalize()
        val lexicalBoundary = allowedRoot.toPath().toAbsolutePath().normalize()
        requireLexicallyContainedNoSymlink(
            lexicalBoundary,
            lexicalRoot,
            "generated output root",
        )
        val root = lexicalRoot.absoluteNormalized()
        val boundary = lexicalBoundary.absoluteNormalized()
        if (root == boundary || !root.startsWith(boundary)) {
            throw GradleException(
                "[kmpSsot] Refusing generated output outside the plugin-owned directory: " +
                    "$root (required descendant of $boundary).",
            )
        }
        replaceOwnedFiles(
            installationRoot = root,
            manifest = root.resolve(OWNERSHIP_MANIFEST_NAME),
            owner = owner,
            files = files,
            exclusiveRoot = true,
            beforeMutation = beforeMutation,
        )
    }

    /**
     * Replaces only manifest-owned files in a larger user-owned tree. Unrelated files
     * are ignored; an unowned file at a requested output path is never overwritten.
     */
    fun replaceInstalledFiles(
        installationRoot: File,
        manifestFile: File,
        projectRoot: File,
        owner: String,
        files: Map<String, ByteArray>,
        inputFingerprint: String? = null,
    ) {
        requireProjectContainedPath(manifestFile, projectRoot, "ownership manifest")
        replaceOwnedFiles(
            installationRoot = installationRoot.toPath().absoluteNormalized(),
            manifest = manifestFile.toPath().absoluteNormalized(),
            owner = owner,
            files = files,
            inputFingerprint = inputFingerprint,
            exclusiveRoot = false,
            beforeMutation = { _, _ -> },
        )
    }

    /**
     * First-contact variant for an explicit source installer. Existing requested
     * outputs are moved to a checksummed recovery area before this plugin claims
     * them; later runs use the ownership manifest and reject manual modifications.
     */
    fun replaceInstalledFilesWithBackup(
        installationRoot: File,
        manifestFile: File,
        backupRoot: File,
        projectRoot: File,
        owner: String,
        files: Map<String, ByteArray>,
        inputFingerprint: String? = null,
        additionalTakeoverFiles: Collection<File> = emptyList(),
        afterTakeoverBeforeInstall: (targets: List<Path>) -> Unit = { _ -> },
    ) {
        requireProjectContainedPath(manifestFile, projectRoot, "ownership manifest")
        requireProjectContainedPath(backupRoot, projectRoot, "installer recovery directory")
        val root = installationRoot.toPath().absoluteNormalized()
        val manifest = manifestFile.toPath().absoluteNormalized()
        val ownershipLock = manifest.resolveSibling("${manifest.fileName}.lock")
        withInstallationLock(manifestFile, projectRoot) {
            requireSafeExistingComponents(manifest)
            val desiredOutputBytes = files.map { (relative, bytes) ->
                val portable = validateRelativePath(relative)
                val target = root.resolve(portable).normalize().also {
                    requireContained(root, it, "installer output target")
                }
                target to bytes
            }.toMap()
            val firstContactOutputs = if (!Files.exists(manifest, NOFOLLOW_LINKS)) {
                desiredOutputBytes.keys.map(Path::toFile)
            } else {
                emptyList()
            }
            val existing = (firstContactOutputs + additionalTakeoverFiles)
                .distinctBy { it.toPath().absoluteNormalized() }
                .filter(File::exists)
            if (existing.isNotEmpty()) {
                val recovery = backupThenRemoveForRecovery(installationRoot, backupRoot, projectRoot, existing)
                try {
                    afterTakeoverBeforeInstall(recovery.targets)
                    replaceOwnedFilesLocked(
                        installationRoot = root,
                        manifest = manifest,
                        owner = owner,
                        files = files,
                        inputFingerprint = inputFingerprint,
                        exclusiveRoot = false,
                        lock = ownershipLock,
                        beforeMutation = { _, _ -> },
                    )
                } catch (failure: Exception) {
                    val restoreFailures = mutableListOf<Throwable>()
                    val preservedExternalChanges = mutableListOf<Path>()
                    recovery.targets.forEach { source ->
                        try {
                            val backup = recovery.backups.getValue(source)
                            val expectedHash = recovery.hashes.getValue(source)
                            val bytes = readRegularFileBounded(
                                backup,
                                MAX_MIGRATION_FILE_BYTES,
                                "installer recovery backup",
                            )
                            if (sha256(bytes) != expectedHash) {
                                throw GradleException("[kmpSsot] Installer recovery backup changed: $backup")
                            }
                            if (currentStateEquals(
                                    source,
                                    bytes,
                                    MAX_MIGRATION_FILE_BYTES,
                                    "installer takeover original",
                                )
                            ) {
                                return@forEach
                            }
                            val transactionBytes = desiredOutputBytes[source]
                            val transactionState = when {
                                !Files.exists(source, NOFOLLOW_LINKS) -> null
                                transactionBytes != null && currentStateEquals(
                                    source,
                                    transactionBytes,
                                    MAX_MIGRATION_FILE_BYTES,
                                    "installer takeover transaction output",
                                ) -> transactionBytes
                                else -> {
                                    preservedExternalChanges.add(source)
                                    return@forEach
                                }
                            }
                            if (!restoreSnapshotIfStillTransactionState(
                                    path = source,
                                    writtenBytes = transactionState,
                                    snapshotBytes = bytes,
                                    maximumBytes = MAX_MIGRATION_FILE_BYTES,
                                    label = "installer takeover rollback",
                                )
                            ) {
                                preservedExternalChanges.add(source)
                                return@forEach
                            }
                            if (
                                sha256Bounded(
                                    source,
                                    MAX_MIGRATION_FILE_BYTES,
                                    "restored installer takeover file",
                                ) != expectedHash
                            ) {
                                throw GradleException("[kmpSsot] Restored installer takeover file failed verification: $source")
                            }
                        } catch (restoreFailure: Exception) {
                            // A writer can recreate an absent takeover path between
                            // the state comparison and create-only restore move.
                            if (restoreFailure is java.nio.file.FileAlreadyExistsException &&
                                Files.exists(source, NOFOLLOW_LINKS)
                            ) {
                                preservedExternalChanges.add(source)
                            } else {
                                restoreFailures.add(restoreFailure)
                            }
                        }
                    }
                    if (restoreFailures.isNotEmpty()) {
                        val preservedDetail = if (preservedExternalChanges.isEmpty()) {
                            ""
                        } else {
                            " Rollback preserved newer external changes at: " +
                                preservedExternalChanges.joinToString()
                        }
                        throw GradleException(
                            "[kmpSsot] Installer takeover failed and recovery of the original files was incomplete." +
                                preservedDetail,
                            failure,
                        ).also { combined -> restoreFailures.forEach(combined::addSuppressed) }
                    }
                    if (preservedExternalChanges.isNotEmpty()) {
                        throw GradleException(
                            "[kmpSsot] Installer takeover failed; rollback preserved newer external changes at: " +
                                preservedExternalChanges.joinToString() +
                                ". Verified recovery copies remain under ${backupRoot.path}.",
                            failure,
                        )
                    }
                    throw GradleException(
                        "[kmpSsot] Installer takeover failed; original files were restored from the verified plan.",
                        failure,
                    )
                }
            } else {
                replaceOwnedFilesLocked(
                    installationRoot = root,
                    manifest = manifest,
                    owner = owner,
                    files = files,
                    inputFingerprint = inputFingerprint,
                    exclusiveRoot = false,
                    lock = ownershipLock,
                    beforeMutation = { _, _ -> },
                )
            }
        }
    }

    /** Coordinate a migration with every installer that owns [manifestFile]. */
    fun <T> withInstallationLock(manifestFile: File, projectRoot: File, action: () -> T): T {
        requireProjectContainedPath(manifestFile, projectRoot, "ownership manifest")
        val manifest = manifestFile.toPath().absoluteNormalized()
        requireSafeExistingComponents(manifest)
        return withExclusiveLock(manifest.resolveSibling("${manifest.fileName}.lock"), action)
    }

    /**
     * Copies every migration target to [backupRoot], records checksums and paths, and
     * only then removes the originals. The returned files are the originals removed
     * (or that would be removed when [dryRun] is true).
     */
    fun backupThenRemove(
        installationRoot: File,
        backupRoot: File,
        projectRoot: File,
        candidates: Collection<File>,
        dryRun: Boolean,
        beforeBackup: (index: Int, source: Path) -> Unit = { _, _ -> },
    ): List<File> {
        val setup = prepareRemoval(installationRoot, backupRoot, projectRoot, candidates)
        if (setup.targets.isEmpty() || dryRun) return setup.targets.map(Path::toFile)
        return executeRemoval(setup, beforeBackup).targets.map(Path::toFile)
    }

    private fun backupThenRemoveForRecovery(
        installationRoot: File,
        backupRoot: File,
        projectRoot: File,
        candidates: Collection<File>,
    ): VerifiedRemovalBatch {
        val setup = prepareRemoval(installationRoot, backupRoot, projectRoot, candidates)
        if (setup.targets.isEmpty()) return VerifiedRemovalBatch(emptyList(), emptyMap(), emptyMap())
        return executeRemoval(setup)
    }

    private fun prepareRemoval(
        installationRoot: File,
        backupRoot: File,
        projectRoot: File,
        candidates: Collection<File>,
    ): RemovalSetup {
        requireProjectContainedPath(installationRoot, projectRoot, "logo migration root")
        requireProjectContainedPath(backupRoot, projectRoot, "logo migration backup directory")
        val root = installationRoot.toPath().absoluteNormalized()
        val backups = backupRoot.toPath().absoluteNormalized()
        requireSafeExistingComponents(root)
        if (backups.startsWith(root) || root.startsWith(backups)) {
            throw GradleException(
                "[kmpSsot] Logo migration backup directory must be separate from the Android resources: $backups",
            )
        }

        var snapshotBytes = 0L
        val snapshots = linkedMapOf<Path, ByteArray>()
        val safeCandidates = candidates.map { file ->
            val (_, resolved) = requireProjectContainedPath(file, projectRoot, "logo migration target")
            file to resolved
        }.distinctBy { (_, resolved) -> resolved }
        val targets = safeCandidates.mapNotNull { (_, path) ->
            requireContained(root, path, "logo migration target")
            if (!Files.exists(path, NOFOLLOW_LINKS)) return@mapNotNull null
            val attrs = safeAttributes(path, "logo migration target")
            if (!attrs.isRegularFile) {
                throw GradleException("[kmpSsot] Refusing to remove non-regular logo artifact: $path")
            }
            val size = attrs.size()
            if (size > MAX_MIGRATION_FILE_BYTES) {
                throw GradleException(
                    "[kmpSsot] Refusing to migrate unexpectedly large logo artifact ($size bytes): $path",
                )
            }
            val bytes = readRegularFileBounded(path, MAX_MIGRATION_FILE_BYTES, "logo migration target")
            snapshotBytes += bytes.size
            if (snapshotBytes > MAX_TRANSACTION_SNAPSHOT_BYTES) {
                throw GradleException(
                    "[kmpSsot] Refusing a migration with more than " +
                        "$MAX_TRANSACTION_SNAPSHOT_BYTES bytes of recovery state.",
                )
            }
            snapshots[path] = bytes
            path
        }
        return RemovalSetup(root, backups, targets, snapshots)
    }

    private fun executeRemoval(
        setup: RemovalSetup,
        beforeBackup: (index: Int, source: Path) -> Unit = { _, _ -> },
    ): VerifiedRemovalBatch {
        val (root, backups, targets, snapshots) = setup
        requireSafeExistingComponents(backups)
        Files.createDirectories(backups)
        requireSafeExistingComponents(backups)

        return withExclusiveLock(backups.resolve(".migration.lock")) {
            backupThenRemoveLocked(root, backups, targets, snapshots, beforeBackup)
        }
    }

    private fun backupThenRemoveLocked(
        root: Path,
        backups: Path,
        targets: List<Path>,
        snapshots: Map<Path, ByteArray>,
        beforeBackup: (index: Int, source: Path) -> Unit,
    ): VerifiedRemovalBatch {
        val manifest = backups.resolve("removal-provenance.tsv")
        val (existingRecords, expectedManifestBytes) = readRemovalManifest(manifest)
        val oldRecords = existingRecords.toMutableSet()
        val newRecords = mutableListOf<String>()
        val verifiedHashes = linkedMapOf<Path, String>()
        val verifiedBackups = linkedMapOf<Path, Path>()

        // Finish and verify every backup before deleting the first source file.
        targets.forEachIndexed { index, source ->
            beforeBackup(index, source)
            val relative = root.relativize(source).portablePath()
            val originalBytes = snapshots.getValue(source)
            val hash = sha256(originalBytes)
            verifiedHashes[source] = hash
            val backupRelative = "files/$hash/$relative"
            val destination = backups.resolve(backupRelative).normalize()
            requireContained(backups, destination, "logo migration backup")
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                val attrs = safeAttributes(destination, "logo migration backup")
                if (
                    !attrs.isRegularFile ||
                    sha256Bounded(destination, MAX_MIGRATION_FILE_BYTES, "logo migration backup") != hash
                ) {
                    throw GradleException("[kmpSsot] Existing logo backup is invalid: $destination")
                }
            } else {
                writeNewNoReplace(destination, originalBytes)
                if (sha256Bounded(destination, MAX_MIGRATION_FILE_BYTES, "logo migration backup") != hash) {
                    throw GradleException("[kmpSsot] Logo backup verification failed: $destination")
                }
            }
            verifiedBackups[source] = destination
            newRecords += listOf(hash, relative, backupRelative).joinToString("\t")
        }

        oldRecords += newRecords
        val manifestText = buildString {
            appendLine(REMOVAL_HEADER)
            oldRecords.sorted().forEach(::appendLine)
        }
        replaceSnapshotSafely(
            path = manifest,
            snapshotBytes = expectedManifestBytes,
            replacementBytes = manifestText.toByteArray(StandardCharsets.UTF_8),
            maximumBytes = MAX_MANIFEST_BYTES,
            label = "removal provenance manifest",
        )

        deleteWithRollback(targets, verifiedBackups, verifiedHashes)
        return VerifiedRemovalBatch(targets, verifiedBackups.toMap(), verifiedHashes.toMap())
    }

    /**
     * Deletes a fully backed-up batch and restores every removed source if any later
     * verification/deletion fails. [beforeDelete] exists solely for deterministic
     * failure testing; production uses the no-op default.
     */
    internal fun deleteWithRollback(
        targets: List<Path>,
        backups: Map<Path, Path>,
        expectedHashes: Map<Path, String>,
        beforeDelete: (index: Int, source: Path) -> Unit = { _, _ -> },
    ) {
        val safeTargets = targets.map { it.normalizedSelectedEntry("logo migration target") }
        require(safeTargets.distinct().size == safeTargets.size) { "Duplicate logo migration targets are not allowed." }
        val safeBackups = targets.indices.associate { index ->
            safeTargets[index] to backups.getValue(targets[index]).normalizedSelectedEntry("logo migration backup")
        }
        val safeHashes = targets.indices.associate { index ->
            safeTargets[index] to expectedHashes.getValue(targets[index])
        }

        // Verify every fallback before moving the first source out of place.
        safeTargets.forEach { source ->
            val backup = safeBackups.getValue(source)
            if (sha256Bounded(backup, MAX_MIGRATION_FILE_BYTES, "verified migration backup") !=
                safeHashes.getValue(source)
            ) {
                throw GradleException("[kmpSsot] Verified migration backup changed: $backup")
            }
        }

        val parked = linkedMapOf<Path, Path>()
        val committedDeletes = mutableSetOf<Path>()
        try {
            safeTargets.forEachIndexed { index, source ->
                beforeDelete(index, source)
                parked[source] = parkHashSnapshotSafely(
                    source,
                    safeHashes.getValue(source),
                    MAX_MIGRATION_FILE_BYTES,
                    "logo migration target",
                )
            }
            val recreated = parked.keys.filter { Files.exists(it, NOFOLLOW_LINKS) }
            if (recreated.isNotEmpty()) {
                throw GradleException(
                    "[kmpSsot] Logo artifacts were recreated during migration and were preserved: " +
                        recreated.joinToString(),
                )
            }
            parked.forEach { (source, displaced) ->
                Files.delete(displaced)
                committedDeletes.add(source)
            }
        } catch (failure: Exception) {
            val restoreFailures = mutableListOf<Exception>()
            val preservedExternalChanges = mutableListOf<Path>()
            parked.entries.toList().asReversed().forEach rollback@{ (source, displaced) ->
                try {
                    if (Files.exists(source, NOFOLLOW_LINKS)) {
                        preservedExternalChanges.add(source)
                        return@rollback
                    }
                    if (Files.exists(displaced, NOFOLLOW_LINKS)) {
                        if (sha256Bounded(displaced, MAX_MIGRATION_FILE_BYTES, "parked logo artifact") !=
                            safeHashes.getValue(source)
                        ) {
                            throw GradleException("Parked logo artifact changed before rollback: $displaced")
                        }
                        if (!moveNewWithoutReplacing(displaced, source)) {
                            preservedExternalChanges.add(source)
                            return@rollback
                        }
                    } else if (source in committedDeletes) {
                        val backupBytes = readRegularFileBounded(
                            safeBackups.getValue(source),
                            MAX_MIGRATION_FILE_BYTES,
                            "verified migration backup",
                        )
                        writeNewNoReplace(source, backupBytes)
                    }
                    if (
                        sha256Bounded(source, MAX_MIGRATION_FILE_BYTES, "restored logo artifact") !=
                        safeHashes.getValue(source)
                    ) {
                        throw GradleException("Restored logo artifact failed checksum verification: $source")
                    }
                } catch (restoreFailure: Exception) {
                    restoreFailures.add(restoreFailure)
                }
            }
            parked.keys.filter { source ->
                source !in preservedExternalChanges && !Files.exists(source, NOFOLLOW_LINKS)
            }.forEach { missing ->
                restoreFailures.add(GradleException("Restored logo artifact is still missing: $missing"))
            }
            if (restoreFailures.isNotEmpty()) {
                throw GradleException(
                    "[kmpSsot] Logo migration failed and automatic rollback was incomplete. " +
                        "Use the checksummed provenance backups to restore files manually.",
                    failure,
                ).also { combined -> restoreFailures.forEach(combined::addSuppressed) }
            }
            if (preservedExternalChanges.isNotEmpty()) {
                throw GradleException(
                    "[kmpSsot] Logo migration failed; rollback preserved newer external changes at: " +
                        preservedExternalChanges.joinToString() +
                        ". Verified recovery copies remain available.",
                    failure,
                )
            }
            throw GradleException(
                "[kmpSsot] Logo migration failed; all removed source artifacts were restored.",
                failure,
            )
        }
    }

    /** Rejects a configured input located inside a directory this task installs into. */
    fun requireInputOutsideOutput(input: File, outputRoot: File, label: String) {
        val source = input.toPath().absoluteNormalized()
        val output = outputRoot.toPath().absoluteNormalized()
        requireSafeExistingComponents(source)
        requireSafeExistingComponents(output)
        if (source.startsWith(output)) {
            throw GradleException(
                "[kmpSsot] $label must be outside the generated output directory $output; got $source.",
            )
        }
    }

    /** Requires an installer destination to be a strict, no-follow descendant of the project root. */
    fun requireInstallerInsideProject(directory: File, projectRoot: File, label: String) {
        val (root, destination) = requireProjectContainedPath(directory, projectRoot, label)
        if (destination == root || !destination.startsWith(root)) {
            throw GradleException(
                "[kmpSsot] Refusing $label outside the root project directory $root: $destination",
            )
        }
    }

    private fun requireProjectContainedPath(candidate: File, projectRoot: File, label: String): Pair<Path, Path> {
        val lexicalRoot = projectRoot.toPath().toAbsolutePath().normalize()
        val lexicalCandidate = candidate.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(lexicalRoot) || Files.isSymbolicLink(lexicalCandidate)) {
            throw GradleException("[kmpSsot] Refusing symlinked $label path: $lexicalCandidate")
        }
        if (lexicalCandidate.startsWith(lexicalRoot)) {
            var cursor = lexicalRoot
            lexicalRoot.relativize(lexicalCandidate).forEach { component ->
                cursor = cursor.resolve(component)
                if (Files.exists(cursor, NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                    throw GradleException("[kmpSsot] Refusing $label through symlink: $cursor")
                }
            }
        }
        val root = projectRoot.toPath().absoluteNormalized()
        val resolved = candidate.toPath().absoluteNormalized()
        requireSafeExistingComponents(root)
        requireSafeExistingComponents(resolved)
        if (resolved != root && !resolved.startsWith(root)) {
            throw GradleException("[kmpSsot] Refusing $label outside the root project directory $root: $resolved")
        }
        return root to resolved
    }

    /** Throws when any existing component is a symlink, junction, or special file. */
    fun requireSafePath(file: File, label: String) {
        try {
            val original = file.toPath().toAbsolutePath().normalize()
            // Platform aliases such as macOS /var may appear above the selected
            // path, but the selected entry itself must never be a symlink.
            if (Files.isSymbolicLink(original)) {
                throw GradleException("path is a symbolic link: $original")
            }
            requireSafeExistingComponents(file.toPath().absoluteNormalized())
        } catch (e: GradleException) {
            throw GradleException("[kmpSsot] Unsafe $label path: ${e.message}", e)
        }
    }

    /** Read-only no-follow containment check shared by diagnostics and installers. */
    fun projectPathExists(file: File, projectRoot: File, label: String): Boolean {
        requireProjectContainedPath(file, projectRoot, label)
        return Files.exists(file.toPath().toAbsolutePath().normalize(), NOFOLLOW_LINKS)
    }

    /** Reads one stable regular-file snapshot without ever exceeding [maximumBytes]. */
    fun readProjectFileBounded(
        file: File,
        projectRoot: File,
        maximumBytes: Long,
        label: String,
    ): ByteArray {
        requireProjectContainedPath(file, projectRoot, label)
        return readRegularFileBounded(
            file.toPath().absoluteNormalized(),
            maximumBytes,
            label,
        )
    }

    private fun replaceOwnedFiles(
        installationRoot: Path,
        manifest: Path,
        owner: String,
        files: Map<String, ByteArray>,
        inputFingerprint: String? = null,
        exclusiveRoot: Boolean,
        beforeMutation: (index: Int, target: Path) -> Unit,
    ) {
        val lock = manifest.resolveSibling("${manifest.fileName}.lock")
        withExclusiveLock(lock) {
            replaceOwnedFilesLocked(
                installationRoot,
                manifest,
                owner,
                files,
                inputFingerprint,
                exclusiveRoot,
                lock,
                beforeMutation,
            )
        }
    }

    private fun replaceOwnedFilesLocked(
        installationRoot: Path,
        manifest: Path,
        owner: String,
        files: Map<String, ByteArray>,
        inputFingerprint: String?,
        exclusiveRoot: Boolean,
        lock: Path,
        beforeMutation: (index: Int, target: Path) -> Unit,
    ) {
        require(owner.matches(Regex("[a-z0-9._-]+"))) { "Invalid ownership id: $owner" }
        require(inputFingerprint == null || inputFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid ownership input fingerprint."
        }
        if (files.isEmpty()) throw GradleException("[kmpSsot] Refusing to install an empty owned output set.")
        if (files.size > MAX_OWNED_OUTPUT_ENTRIES) {
            throw GradleException(
                "[kmpSsot] Refusing to install more than $MAX_OWNED_OUTPUT_ENTRIES owned output files.",
            )
        }
        var desiredBytes = 0L
        files.forEach { (relative, bytes) ->
            if (bytes.size.toLong() > MAX_MIGRATION_FILE_BYTES) {
                throw GradleException(
                    "[kmpSsot] Refusing owned output larger than $MAX_MIGRATION_FILE_BYTES bytes: $relative",
                )
            }
            desiredBytes += bytes.size
            if (desiredBytes > MAX_TRANSACTION_SNAPSHOT_BYTES) {
                throw GradleException(
                    "[kmpSsot] Refusing owned outputs totaling more than " +
                        "$MAX_TRANSACTION_SNAPSHOT_BYTES bytes.",
                )
            }
        }

        requireSafeExistingComponents(installationRoot)
        requireSafeExistingComponents(manifest.parent)
        val desired = files.map { (relative, bytes) ->
            val portable = validateRelativePath(relative)
            val target = installationRoot.resolve(portable).normalize()
            requireContained(installationRoot, target, "generated output")
            OwnedEntry(portable, sha256(bytes)) to bytes
        }.toMap()
        if (desired.size != files.size) {
            throw GradleException("[kmpSsot] Duplicate normalized generated output paths are not allowed.")
        }

        val ownershipSnapshot = readOwnershipManifest(manifest, owner)
        val previous = ownershipSnapshot.entries
        val previousByPath = previous.associateBy(OwnedEntry::relativePath)

        previous.forEach { entry ->
            val target = installationRoot.resolve(entry.relativePath).normalize()
            requireContained(installationRoot, target, "owned output")
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                val attrs = safeAttributes(target, "owned output")
                if (!attrs.isRegularFile) {
                    throw GradleException("[kmpSsot] Owned output is no longer a regular file: $target")
                }
                val actual = sha256Bounded(target, MAX_MIGRATION_FILE_BYTES, "owned output")
                if (actual != entry.sha256) {
                    throw GradleException(
                        "[kmpSsot] Refusing to replace modified generated output: $target. " +
                            "Move your changes to source control or delete the generated directory deliberately.",
                    )
                }
            }
        }

        desired.keys.forEach { entry ->
            val target = installationRoot.resolve(entry.relativePath).normalize()
            if (entry.relativePath !in previousByPath && Files.exists(target, NOFOLLOW_LINKS)) {
                throw GradleException(
                    "[kmpSsot] Refusing to overwrite unowned file at generated path: $target. " +
                        "Move or back up that file, then retry.",
                )
            }
        }

        if (exclusiveRoot && Files.exists(installationRoot, NOFOLLOW_LINKS)) {
            val known = previous.mapTo(mutableSetOf()) { it.relativePath }
            val manifestInRoot = if (manifest.startsWith(installationRoot)) {
                installationRoot.relativize(manifest).portablePath()
            } else {
                null
            }
            val lockInRoot = if (lock.startsWith(installationRoot)) {
                installationRoot.relativize(lock).portablePath()
            } else {
                null
            }
            walkRegularFilesNoFollow(installationRoot).forEach { path ->
                val relative = installationRoot.relativize(path).portablePath()
                if (relative != manifestInRoot && relative != lockInRoot && relative !in known) {
                    throw GradleException(
                        "[kmpSsot] Refusing to clean generated directory containing unowned file: $path. " +
                            "Move it out or remove the generated directory deliberately.",
                    )
                }
            }
        }

        val alreadyCurrent = ownershipSnapshot.inputFingerprint == inputFingerprint &&
            previous.size == desired.size && desired.keys.all { wanted ->
                previousByPath[wanted.relativePath]?.sha256 == wanted.sha256 &&
                    Files.exists(installationRoot.resolve(wanted.relativePath), NOFOLLOW_LINKS)
            }
        if (alreadyCurrent) return

        val newEntries = desired.keys.sortedBy(OwnedEntry::relativePath)
        val manifestText = buildString {
            appendLine(OWNERSHIP_HEADER)
            appendLine("owner\t$owner")
            inputFingerprint?.let { appendLine("input-fingerprint\t$it") }
            newEntries.forEach { appendLine("${it.sha256}\t${it.relativePath}") }
        }
        val manifestBytes = manifestText.toByteArray(StandardCharsets.UTF_8)
        if (manifestBytes.size.toLong() > MAX_MANIFEST_BYTES) {
            throw GradleException(
                "[kmpSsot] Refusing an ownership manifest larger than $MAX_MANIFEST_BYTES bytes.",
            )
        }

        commitOwnedFilesTransaction(
            installationRoot = installationRoot,
            manifest = manifest,
            previous = previous,
            desired = desired,
            manifestBytes = manifestBytes,
            expectedManifestBytes = ownershipSnapshot.bytes,
            beforeMutation = beforeMutation,
        )
    }

    /**
     * Commits an ownership-set replacement as a recoverable batch. Every path that
     * may change is snapshotted before the first write. New content is installed
     * before stale content is removed, and provenance is committed last. Any
     * exception restores the previous files and manifest before it escapes.
     */
    private fun commitOwnedFilesTransaction(
        installationRoot: Path,
        manifest: Path,
        previous: List<OwnedEntry>,
        desired: Map<OwnedEntry, ByteArray>,
        manifestBytes: ByteArray,
        expectedManifestBytes: ByteArray?,
        beforeMutation: (index: Int, target: Path) -> Unit,
    ) {
        val paths = (previous.map { installationRoot.resolve(it.relativePath).normalize() } +
            desired.keys.map { installationRoot.resolve(it.relativePath).normalize() })
            .distinct()
            .sortedBy(Path::toString)
        val snapshots = linkedMapOf<Path, ByteArray?>()
        val previousByPath = previous.associateBy { installationRoot.resolve(it.relativePath).normalize() }
        var snapshotBytes = 0L
        paths.forEach { path ->
            requireContained(installationRoot, path, "owned output transaction")
            val bytes = if (Files.exists(path, NOFOLLOW_LINKS)) {
                val attrs = safeAttributes(path, "owned output transaction")
                if (!attrs.isRegularFile || attrs.size() > MAX_MIGRATION_FILE_BYTES) {
                    throw GradleException("[kmpSsot] Output is too large or unsafe to transact: $path")
                }
                readRegularFileBounded(path, MAX_MIGRATION_FILE_BYTES, "owned output transaction")
            } else {
                null
            }
            val owned = previousByPath[path]
            when {
                owned == null && bytes != null -> throw GradleException(
                    "[kmpSsot] Refusing to overwrite unowned file at generated path: $path. " +
                        "Move or back up that file, then retry.",
                )

                owned != null && bytes != null && sha256(bytes) != owned.sha256 -> throw GradleException(
                    "[kmpSsot] Refusing to replace modified generated output: $path. " +
                        "Move your changes to source control or delete the generated directory deliberately.",
                )
            }
            snapshotBytes += bytes?.size ?: 0
            if (snapshotBytes > MAX_TRANSACTION_SNAPSHOT_BYTES) {
                throw GradleException(
                    "[kmpSsot] Refusing an output transaction with more than " +
                        "$MAX_TRANSACTION_SNAPSHOT_BYTES bytes of recovery state.",
                )
            }
            snapshots[path] = bytes
        }
        verifySnapshotUnchanged(
            manifest,
            expectedManifestBytes,
            MAX_MANIFEST_BYTES,
            "ownership manifest",
        )

        var mutationIndex = 0
        val attempted = linkedMapOf<Path, ByteArray?>()
        var manifestMutationAttempted = false
        try {
            desired.entries.sortedBy { it.key.relativePath }.forEach { (entry, bytes) ->
                val target = installationRoot.resolve(entry.relativePath).normalize()
                beforeMutation(mutationIndex++, target)
                attempted[target] = bytes
                replaceSnapshotSafely(
                    path = target,
                    snapshotBytes = snapshots.getValue(target),
                    replacementBytes = bytes,
                    maximumBytes = MAX_MIGRATION_FILE_BYTES,
                    label = "owned output transaction",
                )
            }
            val desiredPaths = desired.keys.mapTo(hashSetOf()) { it.relativePath }
            previous.filter { it.relativePath !in desiredPaths }.sortedBy { it.relativePath }.forEach { entry ->
                val target = installationRoot.resolve(entry.relativePath).normalize()
                beforeMutation(mutationIndex++, target)
                deleteSnapshotSafely(
                    target,
                    snapshots.getValue(target),
                    MAX_MIGRATION_FILE_BYTES,
                    "owned output transaction",
                )
                attempted[target] = null
            }
            beforeMutation(mutationIndex, manifest)
            manifestMutationAttempted = true
            replaceSnapshotSafely(
                path = manifest,
                snapshotBytes = expectedManifestBytes,
                replacementBytes = manifestBytes,
                maximumBytes = MAX_MANIFEST_BYTES,
                label = "ownership manifest",
            )
            previous.filter { it.relativePath !in desiredPaths }.forEach { entry ->
                pruneEmptyParents(installationRoot.resolve(entry.relativePath).parent, installationRoot)
            }
        } catch (failure: Exception) {
            val rollbackFailures = mutableListOf<Throwable>()
            val preservedExternalChanges = mutableListOf<Path>()
            attempted.entries.toList().asReversed().forEach { (path, writtenBytes) ->
                val bytes = snapshots.getValue(path)
                runCatching {
                    if (currentStateEquals(path, bytes, MAX_MIGRATION_FILE_BYTES, "owned output snapshot")) {
                        return@runCatching
                    }
                    if (!restoreSnapshotIfStillTransactionState(
                            path = path,
                            writtenBytes = writtenBytes,
                            snapshotBytes = bytes,
                            maximumBytes = MAX_MIGRATION_FILE_BYTES,
                            label = "owned output rollback",
                        )
                    ) {
                        preservedExternalChanges.add(path)
                        return@runCatching
                    }
                    if (bytes == null) pruneEmptyParents(path.parent, installationRoot)
                }.exceptionOrNull()?.let(rollbackFailures::add)
            }
            if (manifestMutationAttempted) {
                runCatching {
                    if (currentStateEquals(
                            manifest,
                            expectedManifestBytes,
                            MAX_MANIFEST_BYTES,
                            "ownership manifest snapshot",
                        )
                    ) {
                        return@runCatching
                    }
                    if (!restoreSnapshotIfStillTransactionState(
                            path = manifest,
                            writtenBytes = manifestBytes,
                            snapshotBytes = expectedManifestBytes,
                            maximumBytes = MAX_MANIFEST_BYTES,
                            label = "ownership manifest rollback",
                        )
                    ) {
                        preservedExternalChanges.add(manifest)
                    }
                }.exceptionOrNull()?.let(rollbackFailures::add)
            }
            if (rollbackFailures.isNotEmpty()) {
                throw GradleException(
                    "[kmpSsot] Owned-output installation failed and automatic rollback was incomplete.",
                    failure,
                ).also { combined -> rollbackFailures.forEach(combined::addSuppressed) }
            }
            if (preservedExternalChanges.isNotEmpty()) {
                throw GradleException(
                    "[kmpSsot] Owned-output installation failed; rollback preserved newer external changes at: " +
                        preservedExternalChanges.joinToString(),
                    failure,
                )
            }
            throw GradleException(
                "[kmpSsot] Owned-output installation failed; the previous output set was restored.",
                failure,
            )
        }
    }

    private fun verifySnapshotUnchanged(
        path: Path,
        expected: ByteArray?,
        maximumBytes: Long,
        label: String,
    ) {
        val current = if (Files.exists(path, NOFOLLOW_LINKS)) {
            readRegularFileBounded(path, maximumBytes, label)
        } else {
            null
        }
        val unchanged = when {
            current == null || expected == null -> current == null && expected == null
            else -> current.contentEquals(expected)
        }
        if (!unchanged) {
            throw GradleException("[kmpSsot] $label changed after its transaction snapshot: $path")
        }
    }

    private fun currentStateEquals(
        path: Path,
        expected: ByteArray?,
        maximumBytes: Long,
        label: String,
    ): Boolean {
        if (expected == null) return !Files.exists(path, NOFOLLOW_LINKS)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return false
        return runCatching {
            readRegularFileBounded(path, maximumBytes, label).contentEquals(expected)
        }.getOrDefault(false)
    }

    /**
     * Replace [path] only while it still represents [snapshotBytes]. Existing
     * content is moved aside and verified before the replacement is installed
     * with create-only semantics, closing the verify-then-REPLACE race.
     */
    private fun replaceSnapshotSafely(
        path: Path,
        snapshotBytes: ByteArray?,
        replacementBytes: ByteArray,
        maximumBytes: Long,
        label: String,
    ) {
        val stagedReplacement = stageRollbackSnapshot(path, replacementBytes)
        var parkedCurrent: Path? = null
        var preserveParkedCurrent = false
        try {
            if (snapshotBytes == null) {
                if (!moveNewWithoutReplacing(stagedReplacement, path)) {
                    throw GradleException("[kmpSsot] $label appeared before installation and was not overwritten: $path")
                }
                return
            }

            val parked = moveCurrentPathAside(path) ?: throw GradleException(
                "[kmpSsot] $label disappeared before installation and was not recreated: $path",
            )
            parkedCurrent = parked
            if (!currentStateEquals(parked, snapshotBytes, maximumBytes, label)) {
                if (!moveNewWithoutReplacing(parked, path)) {
                    preserveParkedCurrent = true
                    throw GradleException(
                        "[kmpSsot] $label changed during installation; newer entries were preserved at " +
                            "$path and $parked",
                    )
                }
                throw GradleException("[kmpSsot] $label changed during installation and was not overwritten: $path")
            }
            if (!moveNewWithoutReplacing(stagedReplacement, path)) {
                preserveParkedCurrent = true
                throw GradleException(
                    "[kmpSsot] $label was recreated during installation; the newer entry was preserved at " +
                        "$path and the prior snapshot at $parked",
                )
            }
        } finally {
            Files.deleteIfExists(stagedReplacement)
            parkedCurrent?.let { if (!preserveParkedCurrent) Files.deleteIfExists(it) }
        }
    }

    /** Delete only the exact snapshotted entry, never a concurrent replacement. */
    private fun deleteSnapshotSafely(
        path: Path,
        snapshotBytes: ByteArray?,
        maximumBytes: Long,
        label: String,
    ) {
        if (snapshotBytes == null) {
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                throw GradleException("[kmpSsot] $label appeared before deletion and was preserved: $path")
            }
            return
        }

        val parkedCurrent = moveCurrentPathAside(path) ?: throw GradleException(
            "[kmpSsot] $label disappeared before deletion: $path",
        )
        var preserveParkedCurrent = false
        try {
            if (!currentStateEquals(parkedCurrent, snapshotBytes, maximumBytes, label)) {
                if (!moveNewWithoutReplacing(parkedCurrent, path)) {
                    preserveParkedCurrent = true
                    throw GradleException(
                        "[kmpSsot] $label changed during deletion; newer entries were preserved at " +
                            "$path and $parkedCurrent",
                    )
                }
                throw GradleException("[kmpSsot] $label changed during deletion and was preserved: $path")
            }
            Files.delete(parkedCurrent)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                throw GradleException("[kmpSsot] $label was recreated during deletion and was preserved: $path")
            }
        } finally {
            if (!preserveParkedCurrent) Files.deleteIfExists(parkedCurrent)
        }
    }

    /** Move and verify a checksummed source, retaining it until batch commit. */
    private fun parkHashSnapshotSafely(
        path: Path,
        expectedHash: String,
        maximumBytes: Long,
        label: String,
    ): Path {
        val parkedCurrent = moveCurrentPathAside(path) ?: throw GradleException(
            "[kmpSsot] $label disappeared before deletion: $path",
        )
        var preserveParkedCurrent = false
        try {
            val unchanged = try {
                val attrs = safeAttributes(parkedCurrent, label)
                attrs.isRegularFile && attrs.size() <= maximumBytes &&
                    sha256Bounded(parkedCurrent, maximumBytes, label) == expectedHash
            } catch (verificationFailure: Exception) {
                if (!moveNewWithoutReplacing(parkedCurrent, path)) {
                    preserveParkedCurrent = true
                    verificationFailure.addSuppressed(
                        GradleException(
                            "[kmpSsot] Could not restore unverifiable displaced content at $parkedCurrent; " +
                                "another entry exists at $path",
                        ),
                    )
                }
                throw verificationFailure
            }
            if (!unchanged) {
                if (!moveNewWithoutReplacing(parkedCurrent, path)) {
                    preserveParkedCurrent = true
                    throw GradleException(
                        "[kmpSsot] $label changed during deletion; newer entries were preserved at " +
                            "$path and $parkedCurrent",
                    )
                }
                throw GradleException("[kmpSsot] $label changed during deletion and was preserved: $path")
            }
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                preserveParkedCurrent = true
                throw GradleException("[kmpSsot] $label was recreated during deletion and was preserved: $path")
            }
            preserveParkedCurrent = true
            return parkedCurrent
        } finally {
            if (!preserveParkedCurrent) Files.deleteIfExists(parkedCurrent)
        }
    }

    private fun restoreSnapshotIfStillTransactionState(
        path: Path,
        writtenBytes: ByteArray?,
        snapshotBytes: ByteArray?,
        maximumBytes: Long,
        label: String,
    ): Boolean {
        val parent = path.parent ?: throw GradleException("[kmpSsot] Rollback target has no parent: $path")
        requireSafeExistingComponents(parent)
        Files.createDirectories(parent)
        requireSafeExistingComponents(parent)
        val stagedSnapshot = snapshotBytes?.let { stageRollbackSnapshot(path, it) }
        var preserveStagedSnapshot = false
        var parkedCurrent: Path? = null
        var preserveParkedCurrent = false
        try {
            if (writtenBytes == null) {
                // This transaction deleted the target. Restore only with a
                // create-only move; a concurrent recreation always wins.
                if (snapshotBytes == null) return !Files.exists(path, NOFOLLOW_LINKS)
                if (Files.exists(path, NOFOLLOW_LINKS)) return false
                if (!moveNewWithoutReplacing(stagedSnapshot!!, path)) {
                    preserveStagedSnapshot = true
                    throw GradleException(
                        "[kmpSsot] $label preserved a newer entry at $path; " +
                            "the prior snapshot is available at $stagedSnapshot",
                    )
                }
                return true
            }

            // Move the current entry out of the public path before comparing it.
            // A later writer can now only create a new entry, which restoration
            // will never replace.
            if (Files.exists(path, NOFOLLOW_LINKS) && !isRegularEntryNoFollow(path)) {
                // A symlink, directory, or special entry is necessarily an
                // external replacement. Preserve the entry in place; moving it
                // merely to discover its type would complicate safe rollback.
                return false
            }
            val parked = moveCurrentPathAside(path) ?: return false
            parkedCurrent = parked
            if (!currentStateEquals(parked, writtenBytes, maximumBytes, label)) {
                if (!moveNewWithoutReplacing(parked, path)) {
                    preserveParkedCurrent = true
                    preserveStagedSnapshot = stagedSnapshot != null
                    throw GradleException(
                        "[kmpSsot] $label found newer displaced content at $parked and " +
                            "another newer entry at $path" +
                            (stagedSnapshot?.let { "; the prior snapshot is available at $it" }.orEmpty()),
                    )
                }
                return false
            }

            // The parked bytes belong to this transaction. Install the old
            // snapshot only if no external writer has recreated the target.
            if (stagedSnapshot == null) return !Files.exists(path, NOFOLLOW_LINKS)
            if (!moveNewWithoutReplacing(stagedSnapshot, path)) {
                preserveStagedSnapshot = true
                throw GradleException(
                    "[kmpSsot] $label preserved a newer entry at $path; " +
                        "the prior snapshot is available at $stagedSnapshot",
                )
            }
            return true
        } finally {
            parkedCurrent?.let { if (!preserveParkedCurrent) Files.deleteIfExists(it) }
            stagedSnapshot?.let { if (!preserveStagedSnapshot) Files.deleteIfExists(it) }
        }
    }

    private fun stageRollbackSnapshot(path: Path, bytes: ByteArray): Path {
        val parent = path.parent ?: throw GradleException("[kmpSsot] Output has no parent: $path")
        requireSafeExistingComponents(parent)
        Files.createDirectories(parent)
        requireSafeExistingComponents(parent)
        val temp = Files.createTempFile(parent, ".${path.fileName}.rollback-snapshot-", ".tmp")
        try {
            Files.newByteChannel(temp, WRITE).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                if (channel is FileChannel) channel.force(true)
            }
            return temp
        } catch (failure: Exception) {
            Files.deleteIfExists(temp)
            throw failure
        }
    }

    private fun moveCurrentPathAside(path: Path): Path? {
        val parent = path.parent ?: throw GradleException("[kmpSsot] Transaction target has no parent: $path")
        requireSafeExistingComponents(parent)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val parked = Files.createTempFile(parent, ".${path.fileName}.rollback-current-", ".tmp")
        Files.delete(parked)
        var moved = false
        return try {
            Files.move(path, parked)
            moved = true
            forceDirectory(parent)

            // Inspect the entry that was moved, never its destination. Calling
            // toRealPath() here would follow a concurrently substituted symlink;
            // same-content external files could then be mistaken for the owned
            // snapshot and hashed or deleted. All callers pass already-normalized
            // transaction paths, so the lexical sibling remains the safe handle.
            requireSafeExistingComponents(parent)
            val movedAttributes = Files.readAttributes(
                parked,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            if (!movedAttributes.isRegularFile || movedAttributes.isSymbolicLink || movedAttributes.isOther) {
                throw GradleException(
                    "[kmpSsot] Transaction target changed to a symlink or non-regular entry and was not read: $path",
                )
            }
            parked.toAbsolutePath().normalize()
        } catch (failure: Exception) {
            if (!moved) {
                Files.deleteIfExists(parked)
                if (failure is java.nio.file.NoSuchFileException) return null
                throw failure
            }

            // Validation failed after the public entry was parked. Restore that
            // exact entry with create-only semantics. If another writer has
            // already recreated the public path, retain both entries and surface
            // their locations instead of deleting either one.
            try {
                if (!moveNewWithoutReplacing(parked, path)) {
                    failure.addSuppressed(
                        GradleException(
                            "[kmpSsot] The displaced entry was preserved at $parked because another entry exists at $path.",
                        ),
                    )
                }
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    }

    /** No-follow type probe used before rollback moves an externally replaced path. */
    private fun isRegularEntryNoFollow(path: Path): Boolean = runCatching {
        val parent = path.parent ?: return@runCatching false
        requireSafeExistingComponents(parent)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther
    }.getOrDefault(false)

    /** Create-only move with specified no-replace semantics (unlike `ATOMIC_MOVE`). */
    private fun moveNewWithoutReplacing(source: Path, target: Path): Boolean = try {
        Files.move(source, target)
        forceDirectory(target.parent)
        true
    } catch (failure: Exception) {
        if (Files.exists(target, NOFOLLOW_LINKS)) false else throw failure
    }

    private fun forceDirectory(directory: Path) {
        runCatching { FileChannel.open(directory, READ).use { it.force(true) } }
    }

    private fun readOwnershipManifest(manifest: Path, expectedOwner: String): OwnershipSnapshot {
        if (!Files.exists(manifest, NOFOLLOW_LINKS)) return OwnershipSnapshot(emptyList(), null, null)
        val bytes = readRegularFileBounded(manifest, MAX_MANIFEST_BYTES, "ownership manifest")
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            throw GradleException("[kmpSsot] Ownership manifest is not valid UTF-8: $manifest")
        }
        val lines = text.lineSequence().toList()
        if (lines.size < 2 || lines.first() != OWNERSHIP_HEADER) {
            throw GradleException("[kmpSsot] Unknown ownership manifest format: $manifest")
        }
        val ownerParts = lines[1].split('\t')
        if (ownerParts.size != 2 || ownerParts[0] != "owner" || ownerParts[1] != expectedOwner) {
            throw GradleException("[kmpSsot] Ownership manifest belongs to another generator: $manifest")
        }
        val fingerprintParts = lines.getOrNull(2)?.split('\t')
        val inputFingerprint = if (fingerprintParts?.firstOrNull() == "input-fingerprint") {
            if (fingerprintParts.size != 2 || !fingerprintParts[1].matches(Regex("[0-9a-f]{64}"))) {
                throw GradleException("[kmpSsot] Malformed ownership input fingerprint: $manifest")
            }
            fingerprintParts[1]
        } else {
            null
        }
        val entryStart = if (inputFingerprint == null) 2 else 3
        val seen = mutableSetOf<String>()
        val entries = lines.drop(entryStart).filter(String::isNotBlank).map { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-f]{64}"))) {
                throw GradleException("[kmpSsot] Malformed ownership manifest entry: $manifest")
            }
            val relative = validateRelativePath(parts[1])
            if (!seen.add(relative)) {
                throw GradleException("[kmpSsot] Duplicate ownership manifest entry for $relative")
            }
            OwnedEntry(relative, parts[0])
        }
        return OwnershipSnapshot(entries, bytes, inputFingerprint)
    }

    private fun readRemovalManifest(manifest: Path): Pair<Set<String>, ByteArray?> {
        if (!Files.exists(manifest, NOFOLLOW_LINKS)) return emptySet<String>() to null
        val bytes = readRegularFileBounded(manifest, MAX_MANIFEST_BYTES, "removal provenance manifest")
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            throw GradleException("[kmpSsot] Removal provenance manifest is not valid UTF-8: $manifest")
        }
        val lines = text.lineSequence().toList()
        if (lines.isEmpty() || lines.first() != REMOVAL_HEADER) {
            throw GradleException("[kmpSsot] Unknown removal provenance format: $manifest")
        }
        val records = lines.drop(1).filter(String::isNotBlank).onEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || !parts[0].matches(Regex("[0-9a-f]{64}"))) {
                throw GradleException("[kmpSsot] Malformed removal provenance entry: $manifest")
            }
            validateRelativePath(parts[1])
            validateRelativePath(parts[2])
        }.toSet()
        return records to bytes
    }

    internal fun walkRegularFilesNoFollow(
        root: Path,
        maximumDepth: Int = MAX_OWNED_OUTPUT_DEPTH,
        maximumEntries: Int = MAX_OWNED_OUTPUT_ENTRIES,
    ): List<Path> {
        require(maximumDepth >= 0) { "maximumDepth must not be negative" }
        require(maximumDepth < Int.MAX_VALUE) { "maximumDepth is too large" }
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        val files = mutableListOf<Path>()
        var entries = 0

        fun record(path: Path) {
            entries++
            if (entries > maximumEntries) {
                throw GradleException(
                    "[kmpSsot] Refusing to traverse more than $maximumEntries generated output entries: $path",
                )
            }
        }

        Files.walkFileTree(
            root,
            emptySet<java.nio.file.FileVisitOption>(),
            maximumDepth + 1,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    record(dir)
                    if (root.relativize(dir).nameCount > maximumDepth) {
                        throw GradleException(
                            "[kmpSsot] Refusing generated output deeper than $maximumDepth components: $dir",
                        )
                    }
                    if (attrs.isSymbolicLink || attrs.isOther) {
                        throw GradleException("[kmpSsot] Refusing to traverse unsafe generated directory: $dir")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    record(file)
                    if (attrs.isDirectory && root.relativize(file).nameCount > maximumDepth) {
                        throw GradleException(
                            "[kmpSsot] Refusing generated output deeper than $maximumDepth components: $file",
                        )
                    }
                    if (!attrs.isRegularFile) {
                        throw GradleException("[kmpSsot] Refusing special/symlink output entry: $file")
                    }
                    files.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    record(file)
                    throw GradleException("[kmpSsot] Could not inspect generated output entry: $file", exc)
                }
            },
        )
        return files
    }

    private fun validateRelativePath(value: String): String {
        if (value.isBlank() || '\\' in value || '\t' in value || '\n' in value || '\r' in value) {
            throw GradleException("[kmpSsot] Invalid portable relative output path: $value")
        }
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || ':' in it }) {
            throw GradleException("[kmpSsot] Invalid portable relative output path: $value")
        }
        return segments.joinToString("/")
    }

    /**
     * Lexical containment/no-follow check performed before any real-path
     * normalization can hide a selected symlink behind its destination.
     */
    private fun requireLexicallyContainedNoSymlink(boundary: Path, candidate: Path, label: String) {
        val root = boundary.toAbsolutePath().normalize()
        val path = candidate.toAbsolutePath().normalize()
        if (path != root && !path.startsWith(root)) {
            throw GradleException("[kmpSsot] Refusing $label outside $root: $path")
        }
        if (Files.isSymbolicLink(root)) {
            throw GradleException("[kmpSsot] Refusing symlinked $label boundary: $root")
        }
        var cursor = root
        root.relativize(path).forEach { component ->
            cursor = cursor.resolve(component)
            if (Files.exists(cursor, NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw GradleException("[kmpSsot] Refusing $label through symlink: $cursor")
            }
        }
    }

    private fun requireContained(root: Path, path: Path, label: String) {
        if (path == root || !path.startsWith(root)) {
            throw GradleException("[kmpSsot] Refusing $label outside $root: $path")
        }
    }

    private fun requireSafeExistingComponents(path: Path) {
        var current = path.root ?: throw GradleException("Path must be absolute: $path")
        path.forEach { component ->
            current = current.resolve(component)
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || attrs.isOther) {
                    throw GradleException("path contains a symlink, junction, or special entry: $current")
                }
            }
        }
    }

    private fun safeAttributes(path: Path, label: String): BasicFileAttributes {
        requireSafeExistingComponents(path)
        return try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
                if (it.isSymbolicLink || it.isOther) {
                    throw GradleException("[kmpSsot] Refusing unsafe $label: $path")
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("[kmpSsot] Could not inspect $label: $path", e)
        }
    }

    private fun pruneEmptyParents(start: Path?, stop: Path) {
        var current = start
        while (current != null && current != stop && current.startsWith(stop)) {
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                current = current.parent
                continue
            }
            val attrs = safeAttributes(current, "generated output directory")
            if (!attrs.isDirectory) return
            val empty = Files.newDirectoryStream(current).use { !it.iterator().hasNext() }
            if (!empty) return
            Files.delete(current)
            current = current.parent
        }
    }

    private fun writeNewNoReplace(destination: Path, bytes: ByteArray) {
        val parent = destination.parent
        requireSafeExistingComponents(parent)
        Files.createDirectories(parent)
        requireSafeExistingComponents(parent)
        val temp = Files.createTempFile(parent, ".${destination.fileName}.kmpssot-", ".tmp")
        try {
            Files.newByteChannel(temp, WRITE).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                if (channel is FileChannel) channel.force(true)
            }
            // ATOMIC_MOVE has implementation-specific target-exists behavior and
            // may replace despite the absence of REPLACE_EXISTING. A plain sibling
            // move has the specified create-only semantics required here.
            Files.move(temp, destination)
            forceDirectory(destination.parent)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private inline fun <T> withExclusiveLock(lock: Path, block: () -> T): T {
        val parent = lock.parent ?: throw GradleException("[kmpSsot] Lock has no parent: $lock")
        requireSafeExistingComponents(parent)
        Files.createDirectories(parent)
        requireSafeExistingComponents(parent)
        if (Files.exists(lock, NOFOLLOW_LINKS)) {
            val attrs = safeAttributes(lock, "ownership lock")
            if (!attrs.isRegularFile) throw GradleException("[kmpSsot] Invalid ownership lock: $lock")
        }
        return FileChannel.open(lock, CREATE, WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    /**
     * Normalize through an existing platform prefix (notably macOS `/var` ->
     * `/private/var`) while preserving any not-yet-created suffix. Containment is
     * subsequently checked against a boundary normalized the same way, so a
     * symlink that redirects an output outside that boundary still fails.
     */
    private fun Path.absoluteNormalized(): Path {
        val absolute = toAbsolutePath().normalize()
        val existing = generateSequence(absolute) { it.parent }
            .firstOrNull { Files.exists(it, NOFOLLOW_LINKS) }
            ?: return absolute
        val real = existing.toRealPath()
        return real.resolve(existing.relativize(absolute)).normalize()
    }

    /**
     * Normalize platform aliases through the selected entry's parent without
     * allowing the selected entry itself to be a symlink. Callers have already
     * established project containment for these transaction paths.
     */
    private fun Path.normalizedSelectedEntry(label: String): Path {
        val lexical = toAbsolutePath().normalize()
        if (Files.isSymbolicLink(lexical)) {
            throw GradleException("[kmpSsot] Refusing symlinked $label: $lexical")
        }
        val parent = lexical.parent ?: throw GradleException("[kmpSsot] $label has no parent: $lexical")
        val normalized = parent.absoluteNormalized().resolve(lexical.fileName).normalize()
        requireSafeExistingComponents(normalized)
        return normalized
    }

    private fun Path.portablePath(): String = joinToString("/") { it.toString() }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun readRegularFileBounded(path: Path, maximumBytes: Long, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        consumeRegularFileBounded(path, maximumBytes, label, output::write)
        return output.toByteArray()
    }

    private fun sha256Bounded(path: Path, maximumBytes: Long, label: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        consumeRegularFileBounded(path, maximumBytes, label, digest::update)
        return digest.digest().toHex()
    }

    private inline fun consumeRegularFileBounded(
        path: Path,
        maximumBytes: Long,
        label: String,
        consume: (ByteArray, Int, Int) -> Unit,
    ) {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        val before = safeAttributes(path, label)
        if (!before.isRegularFile || before.size() > maximumBytes) {
            throw GradleException("[kmpSsot] $label is too large or unsafe: $path")
        }
        var total = 0L
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val byteBuffer = java.nio.ByteBuffer.wrap(buffer)
            while (true) {
                byteBuffer.clear()
                val count = channel.read(byteBuffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > maximumBytes - total) {
                    throw GradleException("[kmpSsot] $label grew beyond $maximumBytes bytes while being read: $path")
                }
                consume(buffer, 0, count)
                total += count
            }
        }
        val after = safeAttributes(path, label)
        if (
            total != before.size() ||
            after.size() != before.size() ||
            after.fileKey() != before.fileKey() ||
            after.lastModifiedTime() != before.lastModifiedTime()
        ) {
            throw GradleException("[kmpSsot] $label changed while being read: $path")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
