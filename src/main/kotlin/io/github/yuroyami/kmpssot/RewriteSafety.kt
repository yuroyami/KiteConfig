package io.github.yuroyami.kmpssot

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Suffix appended to a user-owned file before kmpSsot rewrites it (when backups are on). */
internal const val BACKUP_SUFFIX = ".kmpssot.bak"

/**
 * Write [content] to [file] only when it differs from the current content.
 *
 *  - **Idempotent**: identical content is a no-op (returns false).
 *  - **Dry-run**: when [dryRun] is true, logs the intended change and writes
 *    nothing — drives `kmpSsot { dryRun = true }` and the `kmpSsotVerify` flow.
 *  - **Backup**: when [backup] is true and the file already exists, the
 *    pre-write content is copied **once** to `<file>.kmpssot.bak` — an existing
 *    backup is never overwritten, so the earliest pristine copy survives even if
 *    a later run would write already-modified content.
 *  - **Atomic**: the new content is written to a sibling temp file and then moved
 *    into place, so an interrupted run can never leave a truncated/empty file
 *    (critical for `project.pbxproj`).
 *
 * Returns true iff a real write happened.
 */
internal fun writeTextSafely(
    file: File,
    content: String,
    backup: Boolean,
    dryRun: Boolean,
    logger: Logger,
    label: String,
): Boolean {
    val exists = file.exists()
    if (exists && file.readText() == content) return false
    if (dryRun) {
        logger.lifecycle("[kmpSsot][dry-run] would ${if (exists) "update" else "create"} $label (${file.path})")
        return false
    }
    if (backup && exists) backupOnce(file)
    writeAtomically(file, content.toByteArray(Charsets.UTF_8))
    return true
}

/** Byte-oriented sibling of [writeTextSafely] for generated images (plugin-owned, so no backup). */
internal fun writeBytesSafely(
    file: File,
    bytes: ByteArray,
    dryRun: Boolean,
    logger: Logger,
    label: String,
): Boolean {
    if (file.exists() && file.readBytes().contentEquals(bytes)) return false
    if (dryRun) {
        logger.lifecycle("[kmpSsot][dry-run] would write $label (${file.path})")
        return false
    }
    writeAtomically(file, bytes)
    return true
}

/**
 * Copy [file] to `<file>.kmpssot.bak` only if no backup exists yet — write-once,
 * so the first pristine copy is never clobbered by a later (possibly already
 * modified) state.
 */
private fun backupOnce(file: File) {
    val bak = File(file.path + BACKUP_SUFFIX)
    if (!bak.exists()) file.copyTo(bak, overwrite = false)
}

/**
 * Write [bytes] to [target] atomically: stage to a sibling temp file, then move
 * it into place. A crash/OOM/power-loss mid-write leaves the temp file (cleaned
 * up next run via overwrite) but never a truncated [target]. Falls back to a
 * plain replace-move if the filesystem rejects [StandardCopyOption.ATOMIC_MOVE].
 */
private fun writeAtomically(target: File, bytes: ByteArray) {
    val dir = target.absoluteFile.parentFile
    dir?.mkdirs()
    val tmp = File.createTempFile(target.name + ".", BACKUP_SUFFIX + ".tmp", dir)
    try {
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            // Some filesystems (certain network/zip mounts) don't support atomic
            // move — fall back to a best-effort replace.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
