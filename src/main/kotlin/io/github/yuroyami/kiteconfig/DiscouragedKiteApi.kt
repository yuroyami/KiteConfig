package io.github.yuroyami.kiteconfig

/**
 * Marks a KiteConfig switch you should not reach for casually. Using one refuses
 * to compile until the build script opts in:
 *
 * ```kotlin
 * @file:OptIn(io.github.yuroyami.kiteconfig.DiscouragedKiteApi::class)
 * ```
 */
@RequiresOptIn(
    message = "Discouraged KiteConfig switch. Read its KDoc first; what breaks under it is yours to keep.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class DiscouragedKiteApi
