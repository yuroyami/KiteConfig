package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The splash topic: launch-screen art for Android, iOS, and desktop.
 *
 * Every fact defaults to the matching [KiteConfigLogoExtension] fact, so an empty
 * `splash { }` already works. Presence flows the Android splash res (into
 * `build/`, one manifest placeholder line) and the desktop JVM splash image.
 * iOS needs the nested [rewrite] plus `ios { rewrite { } }`, because its
 * delivery edits Info.plist and the asset catalog.
 *
 * | member | meaning | default | flow class |
 * |---|---|---|---|
 * | `image` | splash art | `logo.foreground` | build/ |
 * | `backgroundColor` | plate behind the art | `logo.backgroundColor` | build/ |
 * | `dark { }` | dark-mode variant | none | build/ |
 * | `android { theme }` | app theme the generated style inherits | required for Android | build/ |
 * | `rewrite { }` | arms the iOS delivery | not armed | rewrite |
 * | `skip(p)` / `only(p)` | flow control | flow everywhere | n/a |
 */
abstract class KiteSplashScope @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /** Splash art. Default: `logo.foreground`. */
    abstract val image: RegularFileProperty

    /** Solid plate behind the art, `#RRGGBB` or `#AARRGGBB`. Default: `logo.backgroundColor`. */
    abstract val backgroundColor: Property<String>

    /** Dark-mode overrides. Unset members fall back to the light values. */
    abstract class DarkVariant {
        /** Dark-mode splash art. Default: the light [KiteSplashScope.image]. */
        abstract val image: RegularFileProperty

        /** Dark-mode plate color. Default: none, so no night resources are made. */
        abstract val backgroundColor: Property<String>
    }

    /** Android's splash corner: which app theme the generated style inherits. */
    abstract class AndroidCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.ANDROID

        /**
         * Name of your existing app theme, for example `AppTheme`. The generated
         * `KiteSplash` style inherits it and only adds splash attributes on
         * Android 12 and newer, so older devices see no change at all.
         */
        abstract val theme: Property<String>
    }

    val dark: DarkVariant = objects.newInstance(DarkVariant::class.java)
    val android: AndroidCorner = objects.newInstance(AndroidCorner::class.java)

    /** Platform token for `skip(ios)` / `only(ios)`. */
    val ios: KitePlatformRef = object : KitePlatformRef {
        override val platform: KitePlatform = KitePlatform.IOS
    }

    /** Platform token for `skip(desktop)` / `only(desktop)`. */
    val desktop: KitePlatformRef = object : KitePlatformRef {
        override val platform: KitePlatform = KitePlatform.DESKTOP
    }

    fun dark(action: Action<in DarkVariant>) = action.execute(dark)
    fun android(action: Action<in AndroidCorner>) = action.execute(android)

    internal abstract val declared: Property<Boolean>
    internal abstract val rewriteArmed: Property<Boolean>

    /**
     * Arms the iOS splash delivery: UILaunchScreen in Info.plist plus asset
     * catalog entries, running with kiteRewriteXcode. Source edits, so dryRun,
     * backups, and onConflict apply.
     */
    fun rewrite(action: Action<in KiteSplashRewrite>) {
        rewriteArmed.set(true)
        action.execute(rewriteSpec)
    }

    /** Options for the armed iOS splash delivery. Empty today, kept for growth. */
    abstract class KiteSplashRewrite

    internal val rewriteSpec: KiteSplashRewrite = objects.newInstance(KiteSplashRewrite::class.java)
}
