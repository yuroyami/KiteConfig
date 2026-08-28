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

    /**
     * Whether desktop values are applied at all.
     *
     * Default: `true` once this block is opened. Set it to `false` to keep the
     * configuration in place and skip the work, for example from CI.
     */
    abstract val enabled: Property<Boolean>

    /** Set when the enclosing `desktop { }` block is opened. Presence is the opt-in. */
    internal abstract val configured: Property<Boolean>

    // --- Identity -------------------------------------------------------------

    /**
     * Appended to the root `appId` to build the desktop bundle identifier.
     *
     * Default: empty, so the identifier equals `appId`. A common value is
     * `".desktop"`, which turns `com.example.jetzy` into
     * `com.example.jetzy.desktop`.
     *
     * The result is checked against Apple's reverse-DNS rules, which are the
     * strictest of the three families, so one valid value serves all of them.
     *
     * @throws org.gradle.api.GradleException during configuration when the
     *   resolved identifier is not valid reverse-DNS.
     */
    abstract val idSuffix: Property<String>

    // --- Versions -------------------------------------------------------------

    /**
     * The installer build counter, as one to three numeric dot parts, for example
     * `"42"` or `"1001004000"`.
     *
     * Default: the result of the root `scheme`, rendered as a string. Assigning a
     * value bypasses the scheme, so [rebuild] stops having any effect.
     *
     * @throws org.gradle.api.GradleException during configuration when the root
     *   `version` cannot be parsed or the scheme returns a value out of range.
     */
    abstract val buildNumber: Property<String>

    /**
     * Feeds the scheme as `v.reupload`, so a value of `3` turns `1001004000` into
     * `1001004003`.
     *
     * Default: `0`. Bump it for a re-package that does not bump the version.
     *
     * It is separate from `android.rebuild` and `ios.rebuild` on purpose. The
     * three channels burn numbers on different days.
     */
    abstract val rebuild: Property<Int>

    /**
     * The formula that turns the version into a build number, for desktop only.
     *
     * Default: the root `scheme`. Set it only when desktop genuinely needs a
     * different formula from the mobile platforms, which is rare.
     */
    abstract val scheme: Property<VersionCodeScheme>

    /**
     * Set [scheme] from a lambda: `scheme { v -> ... }`.
     *
     * The lambda receives `v.major`, `v.minor`, `v.patch`, and `v.reupload`.
     * KiteSSOT renders the returned number as the desktop build number.
     */
    fun scheme(s: VersionCodeScheme) {
        scheme.set(s)
    }

    /**
     * The highest build number you already shipped for the current version. The
     * next resolved number must beat it.
     *
     * Default: unset, so no check runs. Comparison is componentwise and numeric,
     * with missing parts read as `0`, so `"2.1"` outranks `"2"`.
     *
     * The check is offline. KiteSSOT never contacts a store, and it never writes
     * this value into any file.
     *
     * @throws org.gradle.api.GradleException during configuration when the
     *   resolved build number does not beat this baseline.
     * @see KiteSsotIosExtension.publishedBuildNumber for the Apple equivalent.
     */
    abstract val publishedBuildNumber: Property<String>

    // --- Icons ----------------------------------------------------------------

    /**
     * Whether KiteSSOT generates the macOS, Windows, and Linux installer icons
     * from your `logo { }` art.
     *
     * Default: `true`, but only while `logo { }` is configured. With no logo
     * block there is nothing to generate from, so this stays off quietly.
     *
     * Generated icons land in the build directory. Your source tree is never
     * touched.
     *
     * @throws org.gradle.api.GradleException during configuration when this is
     *   explicitly `true` and no usable `logo { }` block exists.
     */
    abstract val icons: Property<Boolean>

    /**
     * Whether the generated macOS icon gets the rounded-square mask that Apple's
     * own apps use.
     *
     * Default: `true`. Turn it off when your art already carries its own shape
     * and a second mask would clip it. Only the macOS icon is affected.
     */
    abstract val roundMacOsIcon: Property<Boolean>

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
