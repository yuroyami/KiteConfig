package io.github.yuroyami.kiteconfig

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * One-shot migration helper: removes app-logo files that would otherwise sit as
 * orphans or actively break the resource merge after adopting the FG+BG pipeline.
 *
 * Files migrated (only when present):
 *  - `${androidResDir}/drawable/ic_launcher.xml`: pre-FG/BG generated artifact.
 *  - `${androidResDir}/values/ic_launcher_background.xml`: pre-FG/BG artifact.
 *  - template launcher icons under each `mipmap-<density>` dir (typically
 *    `ic_launcher.webp` from the Android Studio wizard) that collide with the
 *    generated `.png` of the same stem and fail AAPT2's merge.
 *  - on first contact only, any existing file at a path the current installer
 *    will claim, including Android Studio's adaptive-icon XML wrappers and
 *    same-name PNGs. Once an ownership manifest exists, current managed outputs
 *    are preserved and only the sync task may validate or replace them.
 *
 * This task never assumes a familiar filename implies plugin ownership. Every
 * candidate is copied to a checksummed durable `.kiteconfig/recovery` backup and recorded in a
 * provenance manifest before the original is removed. Honors `dryRun` (lists what
 * it would migrate, writes/removes nothing).
 *
 * ## Safety rails on every source-writing task
 *
 * | Rail | Behaviour |
 * |---|---|
 * | Explicit only | never runs as part of an ordinary build; you invoke it by name |
 * | Authorized | the matching DSL block must be configured, or the task is skipped |
 * | `-Pkiteconfig.dryRun=true` | reports what it would write **and remove**, changes nothing |
 * | `-Pkiteconfig.backups=true` | keeps a recovery copy before replacing anything (default) |
 * | Ownership | refuses to overwrite or delete a file it does not own |
 * | Atomic | staged then swapped, so an interrupted run leaves the tree intact |
 *
 * Both `-P` switches accept exactly `true` or `false`; anything else fails the
 * build rather than being read as `false`.
 */
@DisableCachingByDefault(because = "One-shot source migration with backups and provenance side effects.")
abstract class CleanupLegacyAppLogoArtifactsTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Back up, record, then remove legacy/colliding app-logo artifacts."
        outputs.upToDateWhen { false }
        backupDir.convention(project.layout.projectDirectory.dir(".kiteconfig/recovery/android-logo"))
        projectRootDir.convention(project.layout.projectDirectory)
    }

    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val backupDir: DirectoryProperty
    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val dryRun: Property<Boolean>

    /** False when no Android application module was selected or detected. */
    @get:Internal abstract val outputSinkApproved: Property<Boolean>

    @TaskAction
    fun cleanup() {
        if (!outputSinkApproved.getOrElse(false)) {
            throw GradleException(
                "[kiteConfig] No Android application project was found, so there is no directory " +
                    "to clean legacy launcher icons from. Apply com.android.application to a module, " +
                    "or name the sink with modules { androidApps(\":app\") } or " +
                    "modules { androidAppDirectory }.",
            )
        }
        val dry = dryRun.getOrElse(false)
        val resDir = androidResDir.asFile.get()
        OwnedOutputSafety.requireInstallerInsideProject(
            resDir,
            projectRootDir.asFile.get(),
            "Android legacy-logo cleanup root",
        )
        OwnedOutputSafety.requireSafePath(resDir, "Android legacy-logo cleanup root")

        val legacy = listOf(
            "drawable/ic_launcher.xml",
            "values/ic_launcher_background.xml",
        ).map { resDir.resolve(it) }

        val ownershipManifest = resDir.parentFile.resolve(".kiteconfig/android-logo-owned-files-v1")
        val migrated = OwnedOutputSafety.withInstallationLock(ownershipManifest, projectRootDir.asFile.get()) {
            // Before first contact, clear every path the installer will claim so
            // Android Studio templates are recoverably taken over. Once an
            // ownership manifest exists, those current outputs are deliberately
            // excluded: the sync task alone may validate/replace managed files.
            val currentInstallerPaths = if (ownershipManifest.exists()) {
                emptyList()
            } else {
                SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.map(resDir::resolve)
            }
            val targets = legacy + currentInstallerPaths + SyncAndroidLogoTask.collidingTemplateIcons(resDir)
            OwnedOutputSafety.backupThenRemove(
                installationRoot = resDir,
                backupRoot = backupDir.asFile.get(),
                projectRoot = projectRootDir.asFile.get(),
                candidates = targets,
                dryRun = dry,
            )
        }
        migrated.forEach { file ->
            val rel = file.relativeToOrSelf(resDir)
            if (dry) {
                logger.lifecycle("[kiteConfig][dry-run] would back up and remove logo artifact: $rel")
            } else {
                logger.lifecycle("[kiteConfig] Backed up and removed logo artifact: $rel")
            }
        }
        if (!dry && migrated.isNotEmpty()) {
            logger.lifecycle(
                "[kiteConfig] Logo migration recovery files and provenance: ${backupDir.asFile.get()}",
            )
        }
    }
}
