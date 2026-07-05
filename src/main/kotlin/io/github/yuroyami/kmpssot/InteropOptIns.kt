package io.github.yuroyami.kmpssot

/**
 * Opt-in compiler markers propagated to every Kotlin/Native compilation when
 * `kmpSsot { propagateInteropOptIns = true }` (the default).
 *
 * These are the markers a KMP project otherwise sprinkles `@OptIn(...)` for at
 * every Obj-C / cinterop call site (`ExperimentalForeignApi` on cinterop,
 * `ExperimentalForeignApi`/`ExperimentalObjCName` on interop names,
 * `ExperimentalNativeApi` on `kotlin.native.Platform` etc). Setting them once at
 * the toolchain level — only on native compilations, where the markers resolve —
 * removes the per-call-site noise without touching shared code.
 */
internal val DEFAULT_INTEROP_OPT_INS: List<String> = listOf(
    "kotlinx.cinterop.ExperimentalForeignApi",
    "kotlin.experimental.ExperimentalObjCName",
    "kotlin.experimental.ExperimentalNativeApi",
)

/**
 * Default interop markers plus any [extra] the user added via
 * `kmpSsot { extraOptIns += "..." }`, de-duplicated and order-stable (defaults
 * first, then extras in declared order).
 */
internal fun interopOptIns(extra: List<String>): List<String> =
    (DEFAULT_INTEROP_OPT_INS + extra).distinct()
