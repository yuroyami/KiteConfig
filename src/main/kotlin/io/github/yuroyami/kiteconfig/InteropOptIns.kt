package io.github.yuroyami.kiteconfig

/**
 * Opt-in compiler markers propagated to every Kotlin/Native compilation when
 * `kiteConfig { propagateInteropOptIns = true }` (explicit opt-in; default false).
 *
 * These are the markers a KMP project otherwise sprinkles `@OptIn(...)` for at
 * every Obj-C / cinterop call site (`ExperimentalForeignApi` on cinterop,
 * `ExperimentalForeignApi`/`ExperimentalObjCName` on interop names,
 * `ExperimentalNativeApi` on `kotlin.native.Platform` etc). Setting them once at
 * the toolchain level (only on native compilations, where the markers resolve)
 * removes the per-call-site noise without touching shared code.
 */
internal val DEFAULT_INTEROP_OPT_INS: List<String> = listOf(
    "kotlinx.cinterop.ExperimentalForeignApi",
    "kotlin.experimental.ExperimentalObjCName",
    "kotlin.experimental.ExperimentalNativeApi",
)

/**
 * KiteConfig's built-in interop markers, when [includeBuiltIns] is true, plus any
 * [extra] the user added via `optIns { add("...") }`, de-duplicated and
 * order-stable (built-ins first, then extras in declared order).
 */
internal fun interopOptIns(extra: List<String>, includeBuiltIns: Boolean = true): List<String> =
    ((if (includeBuiltIns) DEFAULT_INTEROP_OPT_INS else emptyList()) + extra).distinct()
