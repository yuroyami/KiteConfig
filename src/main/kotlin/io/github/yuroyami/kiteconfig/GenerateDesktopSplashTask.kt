package io.github.yuroyami.kiteconfig

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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/**
 * Where the composed image sits inside [GenerateDesktopSplashTask.outputDir].
 * Compose Desktop copies the `common` subdirectory of `appResourcesRootDir` into
 * the packaged app, which is what lands the file at `APPDIR/resources/splash.png`.
 */
internal const val DESKTOP_SPLASH_RESOURCE_PATH: String = "common/splash.png"

/** Plate used when neither `splash { backgroundColor }` nor `logo { backgroundColor }` is set. */
internal const val DESKTOP_SPLASH_FALLBACK_COLOR: String = "#FFFFFF"

private const val DEFAULT_SPLASH_WIDTH = 800
private const val DEFAULT_SPLASH_HEIGHT = 480

/** The art never takes more than half the canvas height, so the plate frames it. */
private const val SPLASH_ART_HEIGHT_RATIO = 0.5

/**
 * Compose the desktop JVM splash image: a solid plate with the splash art
 * centered on it, written into a plugin-owned `build/` directory.
 *
 * Same automatic tier as [GenerateDesktopIconsTask]: output never leaves
 * `build/`, so there is no ownership manifest in user source, no backups, and no
 * opt-in task. Like the icon generator it
 * ignores `dryRun`: the law scopes dryRun to source rewrites, never build/ flow.
 */
@CacheableTask
abstract class GenerateDesktopSplashTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Compose the desktop JVM splash image from splash { } art."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kiteconfig"))
        canvasWidth.convention(DEFAULT_SPLASH_WIDTH)
        canvasHeight.convention(DEFAULT_SPLASH_HEIGHT)
    }

    /** Optional so that absent art fails with the kiteConfig message instead of Gradle's. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val image: RegularFileProperty

    @get:[Input Optional]
    abstract val backgroundColor: Property<String>

    @get:Input
    abstract val canvasWidth: Property<Int>

    @get:Input
    abstract val canvasHeight: Property<Int>

    /** The Compose `appResourcesRootDir` root; the image itself lands under `common/`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Safety boundary for plugin-owned generated output. */
    @get:Internal
    abstract val generatedRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val art = image.orNull?.asFile ?: throw GradleException(
            "[kiteConfig] The desktop splash has no art. Set kiteConfig { splash { image } } " +
                "or kiteConfig { logo { foreground } }.",
        )
        val target = outputDir.get().asFile.resolve(DESKTOP_SPLASH_RESOURCE_PATH)
        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = outputDir.get().asFile,
            allowedRoot = generatedRoot.get().asFile,
            owner = "desktop-splash",
            files = mapOf(DESKTOP_SPLASH_RESOURCE_PATH to encodePng(compose(art), "desktop splash image")),
        )
        logger.info("[kiteConfig] Generated the desktop splash image: $target")
    }

    /** Plate first, then the art contained in a centered band half the canvas tall. */
    private fun compose(art: File): BufferedImage {
        val width = canvasWidth.get()
        val height = canvasHeight.get()
        val plate = plateColor()
        val foreground = decodeSplashArt(art)
        val band = (height * SPLASH_ART_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).withGraphics {
            color = plate
            fillRect(0, 0, width, height)
            drawContain(foreground, 0, (height - band) / 2, width, band)
        }
    }

    /** Flattened over white so a translucent plate reads the same as it does on Apple icons. */
    private fun plateColor(): Color = try {
        flattenOverWhite(parseLogoBackgroundColor(backgroundColor.getOrElse(DESKTOP_SPLASH_FALLBACK_COLOR)))
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteConfig] The desktop splash background color is not usable. ${e.message}", e)
    }

    private fun decodeSplashArt(file: File): BufferedImage = try {
        readBoundedLogoPng(file, "splash { image }")
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteConfig] ${e.message}", e)
    }
}
