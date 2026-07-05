package io.github.yuroyami.kmpssot

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
 * Files removed (only when present):
 *  - `${androidResDir}/drawable/ic_launcher.xml` — pre-FG/BG generated artefact.
 *  - `${androidResDir}/values/ic_launcher_background.xml` — pre-FG/BG artefact.
 *  - template launcher icons under each `mipmap-<density>` dir (typically
 *    `ic_launcher.webp` from the Android Studio wizard) that collide with the
 *    generated `.png` of the same stem and fail AAPT2's merge.
 *
 * All are launcher assets the plugin replaces, so deletion is safe. Honors
 * `dryRun` (lists what it would delete, removes nothing).
 */
@DisableCachingByDefault(because = "Trivial conditional file deletion.")
abstract class CleanupLegacyAppLogoArtifactsTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Remove app-logo artefacts left behind by pre-FG/BG plugin versions."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val dryRun: Property<Boolean>

    @TaskAction
    fun cleanup() {
        val dry = dryRun.getOrElse(false)
        val resDir = androidResDir.asFile.get()

        val legacy = listOf(
            "drawable/ic_launcher.xml",
            "values/ic_launcher_background.xml",
        ).map { resDir.resolve(it) }

        val targets = legacy + SyncAndroidLogoTask.collidingTemplateIcons(resDir)

        targets.forEach { file ->
            if (!file.exists()) return@forEach
            val rel = file.relativeToOrSelf(resDir)
            if (dry) {
                logger.lifecycle("[kmpSsot][dry-run] would remove legacy/colliding logo artefact: $rel")
                return@forEach
            }
            if (file.delete()) {
                logger.lifecycle("[kmpSsot] Removed legacy/colliding logo artefact: $rel")
            } else {
                logger.warn("[kmpSsot] Failed to remove logo artefact: ${file.path}")
            }
        }
    }
}
