package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
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
 * candidate is copied to a checksummed durable `.kitessot/recovery` backup and recorded in a
 * provenance manifest before the original is removed. Honors `dryRun` (lists what
 * it would migrate, writes/removes nothing).
 */
@DisableCachingByDefault(because = "One-shot source migration with backups and provenance side effects.")
abstract class CleanupLegacyAppLogoArtifactsTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Back up, record, then remove legacy/colliding app-logo artifacts."
        outputs.upToDateWhen { false }
        backupDir.convention(project.layout.projectDirectory.dir(".kitessot/recovery/android-logo"))
        projectRootDir.convention(project.layout.projectDirectory)
    }

    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val backupDir: DirectoryProperty
    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val dryRun: Property<Boolean>

    @TaskAction
    fun cleanup() {
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

        val ownershipManifest = resDir.parentFile.resolve(".kitessot/android-logo-owned-files-v1")
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
                logger.lifecycle("[kiteSsot][dry-run] would back up and remove logo artifact: $rel")
            } else {
                logger.lifecycle("[kiteSsot] Backed up and removed logo artifact: $rel")
            }
        }
        if (!dry && migrated.isNotEmpty()) {
            logger.lifecycle(
                "[kiteSsot] Logo migration recovery files and provenance: ${backupDir.asFile.get()}",
            )
        }
    }
}
