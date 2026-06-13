package io.github.yuroyami.kmpssot

import org.gradle.api.logging.Logger
import java.io.File

/** Suffix appended to a user-owned file before kmpSsot rewrites it (when backups are on). */
internal const val BACKUP_SUFFIX = ".kmpssot.bak"

/**
 * Write [content] to [file] only when it differs from the current content.
 *
 *  - **Idempotent**: identical content is a no-op (returns false).
 *  - **Dry-run**: when [dryRun] is true, logs the intended change and writes
 *    nothing — drives `kmpSsot { dryRun = true }` and the `kmpSsotVerify` flow.
 *  - **Backup**: when [backup] is true and the file already exists, the
 *    pre-write content is copied to `<file>.kmpssot.bak` before the change, so
 *    a mis-detected rewrite of a user-owned file is always recoverable.
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
    if (backup && exists) {
        file.copyTo(File(file.path + BACKUP_SUFFIX), overwrite = true)
    }
    file.parentFile?.mkdirs()
    file.writeText(content)
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
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    return true
}
