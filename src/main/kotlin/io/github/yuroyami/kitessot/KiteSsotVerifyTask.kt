package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Read-only report of the resolved single source of truth and selected platform
 * paths. Provider and filesystem inspection failures are rendered inline rather
 * than aborting the report. Use [KiteSsotPlanTask] to inspect mutation policies
 * and [KiteSsotCheckTask] to gate CI on diagnostics.
 *
 * ## The four read-only tasks
 *
 * | Task | Answers | Fails the build |
 * |---|---|---|
 * | `kiteSsotVerify` | which values did KiteSSOT resolve? | no |
 * | `kiteSsotDoctor` | what is wrong with my setup? | no |
 * | `kiteSsotCheck` | the same, for CI | yes, on ERROR findings |
 * | `kiteSsotPlan` | what would the mutation tasks write? | no |
 *
 * None of the four writes to your source tree, and none of them needs `dryRun`.
 * Colour is added when a real terminal is attached; `NO_COLOR`, `TERM=dumb`, and
 * `--console=plain` each turn it off, and `-Pkitessot.color=true|false` forces it.
 */
@DisableCachingByDefault(because = "Reporting task; prints current state.")
abstract class KiteSsotVerifyTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Report resolved SSOT values + iOS target files. Modifies nothing."
        outputs.upToDateWhen { false }
        androidApplicationProjects.convention(emptyList())
        androidAppDirectories.convention(emptyList())
        colorEnabled.convention(false)
    }

    /** Presentation only, so it never affects up-to-date checks. */
    @get:Internal abstract val colorEnabled: Property<Boolean>
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
    @get:Internal abstract val projectRootDir: DirectoryProperty

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
        val console = KiteSsotConsole(colorEnabled.getOrElse(false))
        val root = runCatching { projectRootDir.asFile.orNull?.toPath() }.getOrNull()

        fun section(title: String) = console.paint("  $title", KiteSsotStyle.SECTION)
        fun rows(vararg entries: Pair<String, String>) =
            alignedRows(entries.toList(), indent = "    ", console = console)

        logger.lifecycle(
            buildString {
                appendLine(console.paint("[kiteSsot] Resolved single source of truth", KiteSsotStyle.HEADING))
                appendLine(section("Identity"))
                rows(
                    "appName" to show(appName),
                    "version" to show(versionName),
                    "versionCode" to derivedVc,
                    "androidApplicationId" to show(androidApplicationId),
                    "iosBundleId" to show(iosBundleId),
                    "locales" to localeDescription,
                    "ios.sync renameTo" to show(iosSharedModuleName),
                ).forEach(::appendLine)
                appendLine(section("Android"))
                rows(
                    "selected projects" to listDescription(androidApplicationProjects),
                    "app directories" to listDescription(androidAppDirectories),
                    "compileSdk" to show(compileSdk),
                    "minSdk" to show(minSdk),
                    "targetSdk" to show(targetSdk),
                    "ndk" to show(ndkVersion),
                    "jvmTarget" to show(javaVersion),
                ).forEach(::appendLine)
                appendLine(section("Toolchain"))
                rows(
                    "nativeOptIns" to show(propagateInteropOptIns),
                    "web.ioWorker" to show(generateIoWorker),
                ).forEach(::appendLine)
                appendLine(section("App logo"))
                rows(
                    "foreground" to foregroundDescription,
                    "background" to logoBackgroundDescription(),
                ).forEach(::appendLine)
                appendLine(section("iOS target files"))
                rows(
                    "pbxproj" to presence(pbxprojFile, root, console),
                    "Info.plist" to presence(infoPlistFile, root, console),
                    "Podfile" to presence(podfile, root, console),
                ).forEach(::appendLine)
                append(
                    console.paint(
                        "  Inspect selected mutation paths and policies with: ./gradlew kiteSsotPlan",
                        KiteSsotStyle.MUTED,
                    ),
                )
            }
        )
    }

    private fun presence(
        p: RegularFileProperty,
        root: java.nio.file.Path?,
        console: KiteSsotConsole,
    ): String {
        return runCatching {
            val f = p.asFile.orNull ?: return console.paint("[path unset]", KiteSsotStyle.MUTED)
            val path = f.toPath()
            val shown = root?.let { relativeDisplayPath(it, path) } ?: f.path
            val display = console.paint(diagnosticSafeText(shown), KiteSsotStyle.PATH)
            when {
                java.nio.file.Files.isSymbolicLink(path) ->
                    console.paint("SYMLINK", KiteSsotStyle.WARN) + " $display"
                java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ->
                    console.paint("found", KiteSsotStyle.PASS) + "   $display"
                java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ->
                    console.paint("OTHER", KiteSsotStyle.WARN) + "   $display"
                else -> console.paint("MISSING", KiteSsotStyle.FAIL) + " $display"
            }
        }.getOrElse { console.paint("ERROR   (${diagnosticExceptionSummary(it)})", KiteSsotStyle.FAIL) }
    }

    private fun logoBackgroundDescription(): String = when {
        runCatching { logoBackgroundColor.isPresent }.getOrElse { false } -> "color ${showSafely(logoBackgroundColor)}"
        runCatching { logoBackground.getOrElse(false) }.getOrElse { false } -> "PNG"
        else -> "[unset]"
    }

    private fun showSafely(property: Property<*>): String =
        runCatching { property.orNull?.toString()?.let(::diagnosticSafeText) ?: "[unset]" }
            .getOrElse { "[error: ${diagnosticExceptionSummary(it)}]" }
}
