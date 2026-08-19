package io.github.yuroyami.kitessot

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * One app icon for both platforms, inside `kiteSsot { logo { ... } }`.
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
 * Hand KiteSSOT a foreground and one background. It renders both platforms from
 * that pair, so nobody exports twenty PNGs by hand again.
 *
 * ## What gets written
 *
 * | Platform | Output |
 * |---|---|
 * | Android | `ic_launcher`, `ic_launcher_round`, and both adaptive layers at every density |
 * | Android, `compileSdk` 33+ | the themed-icon `<monochrome>` wrapper as well |
 * | Apple | one `AppIcon-1024.png` plus a single-image universal `Contents.json` |
 *
 * The Apple side is deliberately one image: Xcode 14 and newer derive every
 * slot from the 1024 universal entry, so shipping the older per-slot set would
 * add files nothing reads.
 *
 * Apple rejects icons that carry transparency, so the Apple output is always
 * flattened onto your background. Android keeps foreground and background as the
 * two separate adaptive layers.
 *
 * ## Configuring is not running
 *
 * Opening this block authorizes the logo tasks. It does not run them. Nothing on
 * disk changes until you invoke `kiteSsotSyncAndroidLogo` or
 * `kiteSsotSyncIosLogo` yourself, and `dryRun` and `backups` still apply when
 * you do.
 *
 * The Apple installer additionally needs [KiteSsotIosExtension.deploymentTarget]
 * and an `ios { sync { } }` block, because it writes into your Xcode tree.
 *
 * @see KiteSsotIosSyncExtension for the gate the Apple installer also requires.
 * @see KiteSsotIosExtension.appIconDirectory for where the Apple catalog lives.
 */
abstract class KiteSsotLogoExtension {

    /**
     * Forces the whole block off without deleting it.
     *
     * Default: `true` as soon as the block is configured. `false` always wins,
     * so CI or a convention plugin can pull the authorization back while the
     * configuration stays in the build file.
     */
    abstract val enabled: Property<Boolean>

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
     * Set this or [backgroundColor], one of the two, never both. Setting both is
     * a configuration error.
     *
     * @throws org.gradle.api.GradleException at task time when both this and
     *   [backgroundColor] are set, or when neither is.
     */
    abstract val background: RegularFileProperty

    /**
     * Solid background color, written as `#RRGGBB` or `#AARRGGBB`. Alpha comes
     * first, the Android convention.
     *
     * Set this or [background], never both. A semi transparent value is
     * flattened over white on the Apple side, because the App Store refuses
     * icons with alpha.
     *
     * @throws org.gradle.api.GradleException at task time when the value is not
     *   `#RRGGBB` or `#AARRGGBB`, or when [background] is also set.
     */
    abstract val backgroundColor: Property<String>

    /**
     * Fraction of Android's adaptive canvas that the foreground may fill.
     *
     * Default: `66.0 / 108.0`, the ratio Android documents. Valid above `0.0`
     * and at most `1.0`. A smaller number adds more padding, which keeps the
     * mark intact under the round, squircle, and teardrop masks launchers apply.
     *
     * @throws org.gradle.api.GradleException at task time when the value is not
     *   in `0.0 < ratio <= 1.0`.
     */
    abstract val androidSafeZone: Property<Double>

    /**
     * Lets Android logo installation claim icon files it did not create: the
     * Android Studio wizard's `ic_launcher.webp`, old adaptive XML wrappers, and
     * same-name PNGs that break the resource merge.
     *
     * Default: `false`. It only acts when a complete replacement logo is
     * configured.
     *
     * Every claimed file is copied to `.kitessot/recovery` and recorded before
     * it is touched, so the takeover is undoable. It still deletes files from
     * your source tree, so treat it as a one-time migration: switch it on, run
     * the task, switch it back off.
     *
     * Run it with `-Pkitessot.dryRun=true` first. The preview lists both the
     * files it would write and the files it would remove.
     */
    abstract val takeOverLegacyIcons: Property<Boolean>
}
