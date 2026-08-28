package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Details for [KiteSsotExtension.version]: the shared formula and platform corners.
 *
 * | member | meaning | default | flow class |
 * |---|---|---|---|
 * | `formula { v -> ... }` | version to every store's build number | [VersionSchemes.DEFAULT] | memory |
 * | corner `reupload` | re-upload counter, feeds the formula | 0 | memory |
 * | corner `shipped` | highest number ever shipped, guard floor | unset | memory |
 * | corner `pin` | hard number, formula skipped | unset | memory |
 * | corner `formula` | platform-only formula override | shared formula | memory |
 * | `ios { marketingVersion }` | shown version on iOS | base version | memory |
 * | `skip(p)` / `only(p)` | flow control | flow everywhere | n/a |
 */
abstract class KiteVersionScope : KiteFlowScope() {

    /** The shared version-to-build-number formula. Corners can override it. */
    internal abstract val formulaProp: Property<VersionCodeScheme>

    /** Set the formula every platform uses unless its corner overrides it. */
    fun formula(s: VersionCodeScheme) = formulaProp.set(s)

    /** Android's version corner: Play counters and the versionCode pin. */
    abstract class AndroidCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.ANDROID

        /** Counter to re-upload the same version to Play. Feeds the formula. */
        abstract val reupload: Property<Int>

        /** Highest versionCode ever shipped. New codes must beat it. */
        abstract val shipped: Property<Int>

        /** Hard versionCode. The formula is skipped. */
        abstract val pin: Property<Int>

        internal abstract val formulaProp: Property<VersionCodeScheme>

        /** Android-only formula override. */
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    /** The iOS version corner: TestFlight counters, pins, and the shown version. */
    abstract class IosCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.IOS

        /** Counter to re-upload the same version to App Store Connect. */
        abstract val reupload: Property<Int>

        /** Highest build number ever shipped. New numbers must beat it. */
        abstract val shipped: Property<String>

        /** Hard buildNumber. The formula is skipped. */
        abstract val pin: Property<String>

        /** Version shown to users. Defaults to the base version. */
        abstract val marketingVersion: Property<String>

        internal abstract val formulaProp: Property<VersionCodeScheme>

        /** iOS-only formula override. */
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    /** The desktop version corner: installer build counters and pins. */
    abstract class DesktopCorner : KitePlatformRef {
        final override val platform: KitePlatform = KitePlatform.DESKTOP

        /** Counter to re-release the same version. Feeds the formula. */
        abstract val reupload: Property<Int>

        /** Highest build number ever shipped. New numbers must beat it. */
        abstract val shipped: Property<String>

        /** Hard build number. The formula is skipped. */
        abstract val pin: Property<String>

        internal abstract val formulaProp: Property<VersionCodeScheme>

        /** Desktop-only formula override. */
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    @get:Inject
    protected abstract val objects: ObjectFactory

    val android: AndroidCorner by lazy { objects.newInstance(AndroidCorner::class.java) }
    val ios: IosCorner by lazy { objects.newInstance(IosCorner::class.java) }
    val desktop: DesktopCorner by lazy { objects.newInstance(DesktopCorner::class.java) }

    fun android(action: Action<in AndroidCorner>) = action.execute(android)
    fun ios(action: Action<in IosCorner>) = action.execute(ios)
    fun desktop(action: Action<in DesktopCorner>) = action.execute(desktop)
}
