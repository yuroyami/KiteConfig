package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property

/**
 * Compose Desktop settings inside `kiteSsot { desktop { ... } }`.
 *
 * The root block holds the truth about your app: its name, its version, its ID.
 * This block holds only what a desktop installer needs on top of that.
 *
 * ```kotlin
 * kiteSsot {
 *     appName = "Jetzy"
 *     version = "1.4.0"
 *     appId = "com.example.jetzy"
 *
 *     desktop {
 *         idSuffix = ".desktop"
 *         rebuild = 2
 *     }
 * }
 * ```
 *
 * Opening the block is the opt-in. `desktop { }` on its own is enough, and every
 * property below already has a working default.
 *
 * ## The three installer families
 *
 * One app produces three very different packages, and each one names things its
 * own way. KiteSSOT derives all three from the values you already set.
 *
 * | Family | Takes its identity from | Its own dial here |
 * |---|---|---|
 * | macOS `.dmg` and `.pkg` | root `appName` and the bundle ID | [roundMacOsIcon] |
 * | Windows `.msi` and `.exe` | root `appName` and the version | [deriveUpgradeUuid] |
 * | Linux `.deb`, `.rpm`, AppImage | a Debian-legal slug of `appName` | [linuxPackageName] |
 *
 * @see KiteSsotModulesExtension.desktopApps to pick which projects this applies to.
 * @see KiteSsotExtension.scheme for the build-number formula every platform shares.
 * @see KiteSsotLogoExtension for the art that [icons] turns into installer icons.
 */
abstract class KiteSsotDesktopExtension {

    // --- Gate -----------------------------------------------------------------


    /** Set when the enclosing `desktop { }` block is opened. Presence is the opt-in. */
    internal abstract val configured: Property<Boolean>

    // --- Identity -------------------------------------------------------------


    // --- Versions -------------------------------------------------------------





    // --- Icons ----------------------------------------------------------------



    // --- Packaging ------------------------------------------------------------

    /**
     * The Debian package name used by the `.deb`, `.rpm`, and AppImage builds,
     * for example `"jetzy"`.
     *
     * Default: a lowercase slug of the root `appName`, with anything Debian
     * rejects replaced by a hyphen. Set it yourself when that slug is not the
     * name you publish under.
     *
     * @throws org.gradle.api.GradleException during configuration when no slug
     *   can be derived from `appName` and no value is set here.
     */
    abstract val linuxPackageName: Property<String>

    /**
     * Whether KiteSSOT fills the Windows upgrade code for you, derived from the
     * root `appId`.
     *
     * Default: `false`. The code is a stable UUIDv5, so the same `appId` always
     * yields the same one, which is what lets an MSI upgrade an install in place
     * rather than sitting beside it.
     *
     * Turning this on for an app you already shipped changes its upgrade code
     * once, on that release: existing installs will not upgrade in place, the
     * same one-time break this feature prevents from then on. Enable it before
     * your first release if you can.
     *
     * An upgrade code you set yourself is always kept. This only fills a blank.
     * Changing `appId` changes the derived code and breaks in-place upgrades for
     * everyone already installed, so pin the old value by hand if you rename.
     */
    abstract val deriveUpgradeUuid: Property<Boolean>
}
