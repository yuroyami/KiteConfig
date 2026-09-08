package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The splash topic: the launch screen for Android, iOS, and desktop.
 *
 * Splash is **off** until you turn it on. Artwork, colors, a dark variant, and
 * an empty block all leave it off, because a launch screen is a visible change
 * to somebody's app and configuring art is not the same as asking for one.
 *
 * ```kotlin
 * kiteConfig {
 *     splash {
 *         enabled = true
 *         dark { backgroundColor = "#000000" }
 *     }
 *     android { splash { theme = "AppTheme" } }
 *     ios { splash { enabled = false } }   // this one platform opts out
 * }
 * ```
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `enabled` | deliver a launch screen | false |
 * | `image` | the art | the effective `logo { foreground }` |
 * | `backgroundColor` | the plate behind it | the effective logo color background |
 * | `dark { }` | dark-mode variant | on once one dark value is set |
 *
 * Every fallback is resolved per platform, so iOS art follows the iOS logo.
 * A logo whose background is an image gives the splash no color: set
 * `splash { backgroundColor }` in that case.
 */
abstract class KiteSplashScope @Inject constructor(private val objects: ObjectFactory) {

    /**
     * Deliver a launch screen. Default: `false` at the root.
     *
     * A platform block inherits this and can disagree, so a root `false` with
     * `ios { splash { enabled = true } }` gives iOS a launch screen and nobody
     * else one.
     */
    abstract val enabled: Property<Boolean>

    /** Splash art. Default: the effective `logo { foreground }` for this platform. */
    abstract val image: RegularFileProperty

    /**
     * Solid plate behind the art, `#RRGGBB` or `#AARRGGBB`, alpha first.
     * Default: the effective logo background, when that background is a color.
     */
    abstract val backgroundColor: Property<String>

    /** Dark-mode overrides. Unset members fall back to the light values. */
    abstract class DarkVariant {

        /** Dark-mode splash art. Default: the light [KiteSplashScope.image]. */
        abstract val image: RegularFileProperty

        /** Dark-mode plate color. Default: the light color. */
        abstract val backgroundColor: Property<String>

        /**
         * Produce dark-mode resources. Default: on once a dark value is set.
         *
         * Setting it false in a platform block drops an inherited dark variant
         * for that platform without touching the splash itself.
         */
        abstract val enabled: Property<Boolean>
    }

    val dark: DarkVariant = objects.newInstance(DarkVariant::class.java)

    fun dark(action: Action<in DarkVariant>) = action.execute(dark)

    internal abstract val rewriteArmed: Property<Boolean>

    /**
     * Arms the iOS launch-screen delivery: the plist entry plus asset catalog
     * entries, running with kiteRewriteXcode. Source edits, so dryRun, backups,
     * and onConflict apply.
     */
    fun rewrite(action: Action<in KiteSplashRewrite>) {
        rewriteArmed.set(true)
        action.execute(rewriteSpec)
    }

    /** Options for the armed iOS splash delivery. Empty today, kept for growth. */
    abstract class KiteSplashRewrite

    internal val rewriteSpec: KiteSplashRewrite = objects.newInstance(KiteSplashRewrite::class.java)
}

/**
 * Android's splash details, inside `kiteConfig { android { splash { ... } } }`.
 *
 * Everything in [KiteSplashScope] applies here too, plus the theme the
 * generated style inherits.
 */
abstract class KiteAndroidSplashScope @Inject constructor(objects: ObjectFactory) : KiteSplashScope(objects) {

    /**
     * Name of your existing app theme, for example `AppTheme`.
     *
     * The generated `KiteSplash` style inherits it and only adds splash
     * attributes on Android 12 and newer, so older devices see no change.
     */
    abstract val theme: Property<String>
}
