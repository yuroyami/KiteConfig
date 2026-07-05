package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Read-only report of the resolved single source of truth and the iOS target
 * files that would be rewritten. Touches nothing — the safe way to sanity-check
 * configuration before a real build. Pair with `kmpSsot { dryRun = true }` plus
 * a sync task to preview the exact edits.
 */
@DisableCachingByDefault(because = "Reporting task; prints current state.")
abstract class KmpSsotVerifyTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Report resolved SSOT values + iOS target files. Modifies nothing."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val androidApplicationId: Property<String>
    @get:Internal abstract val iosBundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val sharedModule: Property<String>
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty

    @get:Internal abstract val androidAppModule: Property<String>
    @get:Internal abstract val compileSdk: Property<Int>
    @get:Internal abstract val minSdk: Property<Int>
    @get:Internal abstract val targetSdk: Property<Int>
    @get:Internal abstract val ndkVersion: Property<String>
    @get:Internal abstract val javaVersion: Property<Int>
    @get:Internal abstract val propagateInteropOptIns: Property<Boolean>
    @get:Internal abstract val generateIoWorker: Property<Boolean>
    @get:Internal abstract val logoForeground: Property<Boolean>
    @get:Internal abstract val logoBackground: Property<Boolean>
    @get:Internal abstract val logoBackgroundColor: Property<String>

    @TaskAction
    fun report() {
        fun show(p: Property<*>) = if (p.isPresent) p.get().toString() else "[unset]"
        val derivedVc = runCatching {
            if (versionCode.isPresent) versionCode.get().toString() else "[unset]"
        }.getOrElse { "ERROR: ${it.message}" }
        logger.lifecycle(
            buildString {
                appendLine("[kmpSsot] Resolved single source of truth:")
                appendLine("  appName              = ${show(appName)}")
                appendLine("  versionName          = ${show(versionName)}")
                appendLine("  versionCode          = $derivedVc")
                appendLine("  androidApplicationId = ${show(androidApplicationId)}")
                appendLine("  iosBundleId          = ${show(iosBundleId)}")
                appendLine("  locales              = ${locales.orNull?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "[none]"}")
                appendLine("  sharedModule         = ${show(sharedModule)}")
                appendLine("  Android:")
                appendLine("    androidAppModule   = ${show(androidAppModule)}")
                appendLine("    compileSdk         = ${show(compileSdk)}")
                appendLine("    minSdk             = ${show(minSdk)}")
                appendLine("    targetSdk          = ${show(targetSdk)}")
                appendLine("    ndkVersion         = ${show(ndkVersion)}")
                appendLine("    javaVersion        = ${show(javaVersion)}")
                appendLine("  Toolchain toggles:")
                appendLine("    propagateInteropOptIns = ${show(propagateInteropOptIns)}")
                appendLine("    web.generateIoWorker   = ${show(generateIoWorker)}")
                appendLine("  App logo:")
                appendLine("    foreground = ${if (logoForeground.getOrElse(false)) "set" else "[unset]"}")
                appendLine("    background = ${logoBackgroundDescription()}")
                appendLine("  iOS target files:")
                appendLine("    pbxproj    ${presence(pbxprojFile)}")
                appendLine("    Info.plist ${presence(infoPlistFile)}")
                appendLine("    Podfile    ${presence(podfile)}")
                append("  Preview exact edits with: kmpSsot { dryRun = true } then run a sync task.")
            }
        )
    }

    private fun presence(p: RegularFileProperty): String {
        val f = p.asFile.orNull ?: return "[path unset]"
        return if (f.exists()) "found   (${f.path})" else "MISSING (${f.path})"
    }

    private fun logoBackgroundDescription(): String = when {
        logoBackgroundColor.isPresent -> "colour ${logoBackgroundColor.get()}"
        logoBackground.getOrElse(false) -> "PNG"
        else -> "[unset]"
    }
}
