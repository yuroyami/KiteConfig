package io.github.yuroyami.kitessot

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Details for [KiteSsotExtension.appName]: per-platform name overrides
 * and flow modifiers. `android("x")` overrides the shown name there.
 */
abstract class KiteAppNameScope @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /** A platform word usable two ways: `ios("value")` and `skip(ios)`. */
    class NameToken internal constructor(
        override val platform: KitePlatform,
        internal val override: Property<String>,
    ) : KitePlatformRef {
        operator fun invoke(value: String) = override.set(value)
    }

    val android: NameToken = NameToken(KitePlatform.ANDROID, objects.property(String::class.java))
    val ios: NameToken = NameToken(KitePlatform.IOS, objects.property(String::class.java))
    val desktop: NameToken = NameToken(KitePlatform.DESKTOP, objects.property(String::class.java))

    internal fun overrideFor(p: KitePlatform): Property<String> = when (p) {
        KitePlatform.ANDROID -> android.override
        KitePlatform.IOS -> ios.override
        KitePlatform.DESKTOP -> desktop.override
    }
}
