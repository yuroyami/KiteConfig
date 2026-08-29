package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets

/** Android 12 draws the splash icon on a 288dp canvas and masks it to a circle. */
private const val SPLASH_CANVAS_DP = 288

/** Visible circle when the icon has no icon background. The art is padded to fit inside it. */
private const val SPLASH_SAFE_CIRCLE_DP = 192

/** The generated style. The manifest placeholder `kiteSplashTheme` points at it. */
internal const val ANDROID_SPLASH_STYLE = "KiteSplash"

private const val SPLASH_COLOR_NAME = "kite_splash_bg"
private const val SPLASH_ICON_NAME = "kite_splash_icon"
private const val SPLASH_RESOURCE_FILE = "kitessot_splash.xml"

/** Resource-name characters only, so an app theme name can never break the generated XML. */
private val ANDROID_STYLE_PARENT = Regex("""[A-Za-z0-9_.:@/]{1,120}""")

/**
 * Generate the Android splash resources from `splash { }` art into a plugin-owned
 * `build/` directory, following [GenerateDesktopIconsTask].
 *
 * `values/` holds a pure `KiteSplash` alias of the app theme, so devices before
 * Android 12 see no change at all. Only `values-v31/` adds the native splash
 * attributes, and `values-night-v31/` overrides the plate color when
 * `splash { dark { } }` sets one. Output never leaves `build/`, so this needs no
 * ownership manifest, no backups, and no opt-in task.
 */
@CacheableTask
abstract class GenerateAndroidSplashTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Generate the Android splash resources from splash { } art."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kitessot"))
    }

    /** Optional so absent art fails with the plugin's own message instead of Gradle's. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val image: RegularFileProperty

    /** Dark-mode art. Present art lands under `drawable-night-*`. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val darkImage: RegularFileProperty

    @get:[Input Optional]
    abstract val backgroundColor: Property<String>

    @get:[Input Optional]
    abstract val darkBackgroundColor: Property<String>

    /** The app theme the generated style inherits. Required: there is no safe default. */
    @get:[Input Optional]
    abstract val theme: Property<String>

    /** Declared output so `res.srcDir(task.flatMap { outputDir })` carries the dependency. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Safety boundary for plugin-owned generated output. */
    @get:Internal
    abstract val generatedRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val parent = validatedThemeName()
        val artFile = image.asFile.orNull ?: throw GradleException(
            "[kiteSsot] The Android splash has no art. Set splash { image }, or logo { foreground } " +
                "to cover the app icon and the splash with one file.",
        )
        val art = decodeSplashArt(artFile, "splash { image }")
        val color = validatedColor(backgroundColor.orNull, "splash { backgroundColor }")
        val darkColor = validatedColor(darkBackgroundColor.orNull, "splash { dark { backgroundColor } }")
        if (color == null) {
            logger.warn(
                "[kiteSsot] The Android splash has no plate color, so Android 12 keeps the window " +
                    "background of your theme. Set splash { backgroundColor } or logo { backgroundColor }.",
            )
        }

        val files = linkedMapOf<String, ByteArray>()
        files["values/$SPLASH_RESOURCE_FILE"] = resourcesXml(aliasStyle(parent))
        files["values-v31/$SPLASH_RESOURCE_FILE"] = resourcesXml(androidTwelveStyle(parent, color))
        if (darkColor != null) {
            files["values-night-v31/$SPLASH_RESOURCE_FILE"] = resourcesXml(colorOnly(darkColor))
        }
        renderIcons(art, "", files)
        darkImage.asFile.orNull?.let { dark ->
            renderIcons(decodeSplashArt(dark, "splash { dark { image } }"), "night-", files)
        }

        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = outputDir.get().asFile,
            allowedRoot = generatedRoot.get().asFile,
            owner = "android-splash",
            files = files,
        )
        logger.info("[kiteSsot] Generated Android splash resources: ${outputDir.get().asFile}")
    }

    /** One PNG per density: the art padded into the masked circle, with no plate baked in. */
    private fun renderIcons(art: BufferedImage, qualifierPrefix: String, into: MutableMap<String, ByteArray>) {
        val ratio = SPLASH_SAFE_CIRCLE_DP.toDouble() / SPLASH_CANVAS_DP
        // The density table the launcher pipeline already uses.
        SyncAndroidLogoTask.DENSITIES.forEach { (density, scale) ->
            val canvas = (SPLASH_CANVAS_DP * scale).toInt()
            into["drawable-$qualifierPrefix$density/$SPLASH_ICON_NAME.png"] =
                encodePng(padToSafeZone(art, canvas, ratio), "Android splash icon")
        }
    }

    /** Pre-Android-12 resources: an alias and nothing else, so old devices are untouched. */
    private fun aliasStyle(parent: String): String =
        """    <style name="$ANDROID_SPLASH_STYLE" parent="$parent"/>""" + "\n"

    /** Android 12 and newer: the same style plus the native splash attributes. */
    private fun androidTwelveStyle(parent: String, color: String?): String = buildString {
        if (color != null) appendLine("""    <color name="$SPLASH_COLOR_NAME">$color</color>""")
        appendLine("""    <style name="$ANDROID_SPLASH_STYLE" parent="$parent">""")
        if (color != null) {
            appendLine(
                """        <item name="android:windowSplashScreenBackground">@color/$SPLASH_COLOR_NAME</item>""",
            )
        }
        appendLine(
            """        <item name="android:windowSplashScreenAnimatedIcon">@drawable/$SPLASH_ICON_NAME</item>""",
        )
        appendLine("    </style>")
    }

    private fun colorOnly(color: String): String =
        """    <color name="$SPLASH_COLOR_NAME">$color</color>""" + "\n"

    private fun resourcesXml(body: String): ByteArray =
        ("""<?xml version="1.0" encoding="utf-8"?>""" + "\n<resources>\n$body</resources>\n")
            .toByteArray(StandardCharsets.UTF_8)

    /** Reuses the logo hex rule but names the member the value actually came from. */
    private fun validatedColor(value: String?, label: String): String? {
        if (value == null) return null
        try {
            validateLogoBackgroundColorHex(value)
        } catch (invalid: IllegalArgumentException) {
            throw GradleException(
                "[kiteSsot] $label must be #RRGGBB or #AARRGGBB (Android convention, alpha first), " +
                    "got: ${diagnosticSafeText(value, 32)}",
                invalid,
            )
        }
        return value
    }

    private fun validatedThemeName(): String {
        val name = theme.orNull.orEmpty().trim()
        if (name.isEmpty()) {
            throw GradleException(
                "[kiteSsot] The Android splash needs the theme it inherits. Set " +
                    "splash { android { theme = \"AppTheme\" } } to the theme your manifest already names.",
            )
        }
        if (!ANDROID_STYLE_PARENT.matches(name)) {
            throw GradleException(
                "[kiteSsot] splash { android { theme } } \"${diagnosticSafeText(name, 120)}\" is not a style " +
                    "name. Write it the way your manifest spells it, for example \"AppTheme\" or \"Theme.MyApp\".",
            )
        }
        return name
    }

    private fun decodeSplashArt(file: File, label: String): BufferedImage = try {
        readBoundedLogoPng(file, label)
    } catch (invalid: IllegalArgumentException) {
        throw GradleException("[kiteSsot] ${invalid.message}", invalid)
    }
}
