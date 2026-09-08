package io.github.yuroyami.kiteconfig

/**
 * Keeps every block inside its own scope.
 *
 * Without this, a nested block can reach the one around it, and the code does
 * something other than what the indentation says. Both of these used to
 * compile, and neither did what it looks like:
 *
 * ```text
 * kiteConfig {
 *     android { skip(ios) }           // called the ROOT skip: excluded iOS everywhere
 *     version { ios { android { } } } // configured the sibling, not the nested one
 * }
 * ```
 *
 * With the marker, both stop compiling. Reaching outwards on purpose still
 * works, spelled out: `this@kiteConfig.skip(ios)`.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class KiteDsl
