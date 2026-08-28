package io.github.yuroyami.kitessot

import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty

/** The three delivery targets a fact can flow to. */
enum class KitePlatform { ANDROID, IOS, DESKTOP }

/** Anything that can stand for a platform in skip()/only(). */
interface KitePlatformRef {
    val platform: KitePlatform
}

/**
 * Base of every topic scope. skip()/only() beside the fact are the only
 * flow control in the DSL. Default: flow everywhere.
 */
abstract class KiteFlowScope {

    internal abstract val skipped: SetProperty<KitePlatform>
    internal abstract val allowed: SetProperty<KitePlatform>

    /** This fact does not flow to the given platforms. */
    fun skip(vararg refs: KitePlatformRef) = refs.forEach { skipped.add(it.platform) }

    /** This fact flows only to the given platforms. */
    fun only(vararg refs: KitePlatformRef) = refs.forEach { allowed.add(it.platform) }

    internal fun flowsTo(p: KitePlatform): Provider<Boolean> =
        skipped.zip(allowed) { s, o -> p !in s && (o.isEmpty() || p in o) }
}
