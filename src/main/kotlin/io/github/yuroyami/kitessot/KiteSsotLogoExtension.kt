package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The logo topic, inside `kiteSsot { logo { ... } }`: one icon for every platform.
 *
 * ```kotlin
 * kiteSsot {
 *     logo {
 *         foreground = file("art/logo_fg.png")
 *         backgroundColor = "#102A43"
 *     }
 * }
 * ```
 *
 * Declaring art is a fact, not an action. Desktop app icons flow into `build/`
 * automatically when a desktop app exists (stop that with `skip(desktop)`).
 * Android res and the iOS asset catalog are source, so they need the nested
 * [rewrite] block, and the armed task only runs when you invoke it by name.
 *
 * | Platform | Output |
 * |---|---|
 * | Android | `ic_launcher`, `ic_launcher_round`, both adaptive layers, every density |
 * | Android, `compileSdk` 33+ | the themed-icon `<monochrome>` wrapper as well |
 * | Apple | one `AppIcon-1024.png` plus a single-image universal `Contents.json` |
 * | Desktop | `.icns`, `.ico`, and `.png` wired into Compose Desktop packaging |
 *
 * Apple rejects icons that carry transparency, so the Apple output is always
 * flattened onto your background. Android keeps the two adaptive layers.
 */
abstract class KiteSsotLogoExtension @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /**
     * PNG holding the artwork that sits on top: your mark, your glyph.
     *
     * A square 1024 by 1024 image with transparency works best. The aspect ratio
     * is preserved, and Android's adaptive safe zone is applied for you.
     */
    abstract val foreground: RegularFileProperty

    /**
     * PNG holding the plate the foreground sits on.
     *
     * Set this or [backgroundColor], one of the two, never both.
     *
     * @throws org.gradle.api.GradleException at task time when both this and
     *   [backgroundColor] are set, or when neither is.
     */
    abstract val background: RegularFileProperty

    /**
     * Solid background color, written as `#RRGGBB` or `#AARRGGBB`. Alpha comes
     * first, the Android convention. Set this or [background], never both.
     *
     * @throws org.gradle.api.GradleException at task time when the value is not
     *   `#RRGGBB` or `#AARRGGBB`, or when [background] is also set.
     */
    abstract val backgroundColor: Property<String>

    /** Android's logo corner: the adaptive-icon safe zone. */
    abstract class AndroidCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.ANDROID

        /**
         * Fraction of the adaptive canvas the foreground may fill.
         * Default: `66.0 / 108.0`, the ratio Android documents.
         *
         * @throws org.gradle.api.GradleException at task time when not in `0.0 < ratio <= 1.0`.
         */
        abstract val safeZone: Property<Double>
    }

    /** Desktop's logo corner: icon shaping for packaging. */
    abstract class DesktopCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.DESKTOP

        /** Round the generated macOS icon. Default: true, the macOS style. */
        abstract val roundMac: Property<Boolean>
    }

    /** Options for the armed source rewrite. */
    abstract class LogoRewrite {

        /**
         * Also claim and remove legacy launcher icons the plugin did not create.
         * Default: false. Claimed files are copied to `.kitessot/recovery` first.
         */
        abstract val replaceOld: Property<Boolean>
    }

    val android: AndroidCorner = objects.newInstance(AndroidCorner::class.java)
    val desktop: DesktopCorner = objects.newInstance(DesktopCorner::class.java)

    fun android(action: Action<in AndroidCorner>) = action.execute(android)
    fun desktop(action: Action<in DesktopCorner>) = action.execute(desktop)

    internal val rewriteSpec: LogoRewrite = objects.newInstance(LogoRewrite::class.java)
    internal abstract val rewriteArmed: Property<Boolean>
    internal abstract val declared: Property<Boolean>

    /**
     * Arms the logo task that writes Android res and the iOS asset catalog.
     * Nothing runs until you invoke the task by name; dryRun and backups apply.
     */
    fun rewrite(action: Action<in LogoRewrite>) {
        rewriteArmed.set(true)
        action.execute(rewriteSpec)
    }
}
