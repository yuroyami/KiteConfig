package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Read-only report of the resolved single source of truth and selected platform
 * paths. Provider and filesystem inspection failures are rendered inline rather
 * than aborting the report. Use [KmpSsotPlanTask] to inspect mutation policies
 * and [KmpSsotCheckTask] to gate CI on diagnostics.
 */
@DisableCachingByDefault(because = "Reporting task; prints current state.")
abstract class KmpSsotVerifyTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Report resolved SSOT values + iOS target files. Modifies nothing."
        outputs.upToDateWhen { false }
        androidApplicationProjects.convention(emptyList())
        androidAppDirectories.convention(emptyList())
    }

    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val androidApplicationId: Property<String>
    @get:Internal abstract val iosBundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val iosSharedModuleName: Property<String>
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty

    @get:Internal abstract val androidApplicationProjects: ListProperty<String>
    @get:Internal abstract val androidAppDirectories: ListProperty<String>
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
        fun show(p: Property<*>) = runCatching {
            if (p.isPresent) diagnosticSafeText(p.get().toString()) else "[unset]"
        }.getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
        val localeDescription = runCatching {
            locales.orNull?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { diagnosticSafeText(it, 256) }
                ?: "[none]"
        }.getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
        fun listDescription(values: ListProperty<String>) = runCatching {
            values.getOrElse(emptyList()).takeIf { it.isNotEmpty() }
                ?.take(100)
                ?.joinToString(", ") { diagnosticSafeText(it, 256) }
                ?: "[none]"
        }.getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
        val foregroundDescription = runCatching {
            if (logoForeground.getOrElse(false)) "set" else "[unset]"
        }.getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
        val derivedVc = runCatching {
            if (versionCode.isPresent) versionCode.get().toString() else "[unset]"
        }.getOrElse { "ERROR: ${diagnosticExceptionSummary(it)}" }
        logger.lifecycle(
            buildString {
                appendLine("[kmpSsot] Resolved single source of truth:")
                appendLine("  appName              = ${show(appName)}")
                appendLine("  versionName          = ${show(versionName)}")
                appendLine("  versionCode          = $derivedVc")
                appendLine("  androidApplicationId = ${show(androidApplicationId)}")
                appendLine("  iosBundleId          = ${show(iosBundleId)}")
                appendLine("  locales              = $localeDescription")
                appendLine("  iosSharedModuleName  = ${show(iosSharedModuleName)}")
                appendLine("  Android:")
                appendLine("    selected projects = ${listDescription(androidApplicationProjects)}")
                appendLine("    app directories   = ${listDescription(androidAppDirectories)}")
                appendLine("    compileSdk         = ${show(compileSdk)}")
                appendLine("    minSdk             = ${show(minSdk)}")
                appendLine("    targetSdk          = ${show(targetSdk)}")
                appendLine("    ndkVersion         = ${show(ndkVersion)}")
                appendLine("    javaVersion        = ${show(javaVersion)}")
                appendLine("  Toolchain toggles:")
                appendLine("    propagateInteropOptIns = ${show(propagateInteropOptIns)}")
                appendLine("    web.generateIoWorker   = ${show(generateIoWorker)}")
                appendLine("  App logo:")
                appendLine("    foreground = $foregroundDescription")
                appendLine("    background = ${logoBackgroundDescription()}")
                appendLine("  iOS target files:")
                appendLine("    pbxproj    ${presence(pbxprojFile)}")
                appendLine("    Info.plist ${presence(infoPlistFile)}")
                appendLine("    Podfile    ${presence(podfile)}")
                append("  Inspect selected mutation paths and policies with: ./gradlew kmpSsotPlan")
            }
        )
    }

    private fun presence(p: RegularFileProperty): String {
        return runCatching {
            val f = p.asFile.orNull ?: return "[path unset]"
            val path = f.toPath()
            val displayPath = diagnosticSafeText(f.path)
            when {
                java.nio.file.Files.isSymbolicLink(path) -> "SYMLINK ($displayPath)"
                java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ->
                    "found   ($displayPath)"
                java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ->
                    "OTHER   ($displayPath)"
                else -> "MISSING ($displayPath)"
            }
        }.getOrElse { "ERROR   (${diagnosticExceptionSummary(it)})" }
    }

    private fun logoBackgroundDescription(): String = when {
        runCatching { logoBackgroundColor.isPresent }.getOrElse { false } -> "colour ${showSafely(logoBackgroundColor)}"
        runCatching { logoBackground.getOrElse(false) }.getOrElse { false } -> "PNG"
        else -> "[unset]"
    }

    private fun showSafely(property: Property<*>): String =
        runCatching { property.orNull?.toString()?.let(::diagnosticSafeText) ?: "[unset]" }
            .getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
}
