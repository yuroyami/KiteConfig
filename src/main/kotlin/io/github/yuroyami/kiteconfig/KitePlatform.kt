package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty

/**
 * The three delivery targets a fact can flow to.
 *
 * Usable directly in [KiteFlowScope.skip] and [KiteFlowScope.only], so
 * `skip(KitePlatform.DESKTOP)` and `skip(desktop)` both compile.
 */
public enum class KitePlatform : KitePlatformRef {
    ANDROID, IOS, DESKTOP;

    override val platform: KitePlatform get() = this
}

/** Anything that can stand for a platform in skip()/only(). */
public interface KitePlatformRef {
    public val platform: KitePlatform
}

/**
 * Root-level platform selection, giving `kiteConfig { }` its [skip] and [only].
 *
 * This used to sit under every topic, so a fact could be excluded in a dozen
 * places. Exclusion now lives in exactly one: the root block. A platform turns
 * one value off for itself with that topic's own `enabled`.
 *
 * Default: every selected platform.
 */
public abstract class KiteFlowScope {

    internal abstract val skipped: SetProperty<KitePlatform>
    internal abstract val allowed: SetProperty<KitePlatform>

    /** This fact does not flow to the given platforms. */
    public fun skip(vararg refs: KitePlatformRef): Unit = refs.forEach { skipped.add(it.platform) }

    /** This fact flows only to the given platforms. */
    public fun only(vararg refs: KitePlatformRef): Unit = refs.forEach { allowed.add(it.platform) }

    internal fun flowsTo(p: KitePlatform): Provider<Boolean> =
        skipped.zip(allowed) { s, o -> p !in s && (o.isEmpty() || p in o) }
}
