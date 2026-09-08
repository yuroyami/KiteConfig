package io.github.yuroyami.kiteconfig

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import java.io.File
import java.io.Serializable

/**
 * What sits behind the app icon: one choice, not two properties.
 *
 * Build one with [KiteLogoScope.color] or [KiteLogoScope.image]. Replacing a
 * color with an image leaves no color behind, which is the trap two separate
 * properties set for anyone overriding artwork per platform.
 */
sealed interface LogoBackground : Serializable {

    /** A solid plate, written as `#RRGGBB` or `#AARRGGBB`, alpha first. */
    data class Color(val hex: String) : LogoBackground

    /** A PNG behind the foreground. */
    data class Image(val file: File) : LogoBackground
}

/**
 * The logo topic, inside `kiteConfig { logo { ... } }`: one icon for every platform.
 *
 * ```kotlin
 * kiteConfig {
 *     logo {
 *         foreground = file("art/logo.png")
 *         background = color("#102A43")
 *     }
 *     android { logo { foregroundScale = 0.61 } }
 * }
 * ```
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `foreground` | the mark that sits on top | none, and the topic stays off |
 * | `background` | `color("#...")` or `image(file(...))` | none |
 * | `foregroundScale` | how much of the canvas the mark fills | each renderer's own ratio |
 * | `enabled` | deliver the icon to this platform | on once `foreground` is set |
 *
 * | Platform | Output |
 * |---|---|
 * | Android | `ic_launcher`, `ic_launcher_round`, both adaptive layers, every density |
 * | Android, `compileSdk` 33+ | the themed-icon `<monochrome>` wrapper as well |
 * | Apple | one `AppIcon-1024.png` plus a single-image universal `Contents.json` |
 * | Desktop | `.icns`, `.ico`, and `.png` wired into Compose Desktop packaging |
 *
 * Apple rejects icons that carry transparency, so the Apple output is always
 * flattened onto the background. Android keeps the two adaptive layers.
 */
@KiteDsl
abstract class KiteLogoScope {

    /**
     * PNG holding the artwork that sits on top: your mark, your glyph.
     *
     * A square 1024 by 1024 image with transparency works best. The aspect ratio
     * is preserved. Setting this is what turns the topic on.
     */
    abstract val foreground: RegularFileProperty

    /**
     * What sits behind [foreground]: `color("#102A43")` or `image(file("bg.png"))`.
     *
     * @throws org.gradle.api.GradleException at task time when a color is not
     *   `#RRGGBB` or `#AARRGGBB`.
     */
    abstract val background: Property<LogoBackground>

    /**
     * How much of the icon canvas the foreground may fill, as a ratio in `0 < s <= 1`.
     *
     * The mark keeps its aspect ratio, is centred, and fits inside a box that is
     * this fraction of the canvas on each axis. Transparent padding inside your
     * PNG counts as part of the image. The background always fills the whole
     * canvas, and each platform's own mask is applied afterwards.
     *
     * Unset, every renderer keeps the ratio it has always used, so existing
     * icons do not move.
     *
     * @throws org.gradle.api.GradleException at task time when not in `0.0 < s <= 1.0`.
     */
    abstract val foregroundScale: Property<Double>

    /** Deliver the icon to this platform. Default: on once [foreground] is set. */
    abstract val enabled: Property<Boolean>

    /** A solid plate behind the mark. */
    fun color(hex: String): LogoBackground = LogoBackground.Color(hex)

    /** A PNG behind the mark. */
    fun image(file: File): LogoBackground = LogoBackground.Image(file)

    /** A PNG behind the mark, from a Gradle file provider. */
    fun image(file: org.gradle.api.file.RegularFile): LogoBackground = LogoBackground.Image(file.asFile)

}

/**
 * Desktop's logo details, inside `kiteConfig { desktop { logo { ... } } }`.
 *
 * Everything in [KiteLogoScope] applies here too, plus the macOS shape.
 */
@KiteDsl
abstract class KiteDesktopLogoScope : KiteLogoScope() {

    /** Round the generated macOS icon. Default: `true`, the macOS style. */
    abstract val roundMac: Property<Boolean>
}


