package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Details for [KiteSsotExtension.id]: per-platform suffixes and flow. */
abstract class KiteIdScope @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /** One platform's identity deviation: [suffix] appended to the base. */
    class IdCorner internal constructor(
        override val platform: KitePlatform,
        val suffix: Property<String>,
    ) : KitePlatformRef

    val android: IdCorner = IdCorner(KitePlatform.ANDROID, objects.property(String::class.java))
    val ios: IdCorner = IdCorner(KitePlatform.IOS, objects.property(String::class.java))
    val desktop: IdCorner = IdCorner(KitePlatform.DESKTOP, objects.property(String::class.java))

    fun android(action: Action<in IdCorner>) = action.execute(android)
    fun ios(action: Action<in IdCorner>) = action.execute(ios)
    fun desktop(action: Action<in IdCorner>) = action.execute(desktop)

    internal fun suffixFor(p: KitePlatform): Property<String> = when (p) {
        KitePlatform.ANDROID -> android.suffix
        KitePlatform.IOS -> ios.suffix
        KitePlatform.DESKTOP -> desktop.suffix
    }
}
