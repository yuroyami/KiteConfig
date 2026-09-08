package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException

internal const val PLAY_VERSION_CODE_CEILING = 2_100_000_000

private const val DEFAULT_MAX_MAJOR = 999
private const val DEFAULT_MAX_MINOR = 999
private const val DEFAULT_MAX_PATCH = 99
private const val DEFAULT_MAX_REBUILD = 9

/**
 * The four numbers KiteConfig hands to a build-number scheme.
 *
 * `version = "1.4.0"` splits into [major], [minor], and [patch]. [rebuild] is the
 * extra dial you turn when a store burns an upload. A scheme reads all four and
 * returns one build number as text.
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"
 *     version {
 *         scheme { v -> (1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild).toString() }
 *     }
 * }
 * ```
 *
 * You never construct one. KiteConfig parses the version, adds the platform's
 * `rebuild` dial, and passes the result into your lambda.
 *
 * | From | Field | `1.4.0` with `rebuild = 2` |
 * |---|---|---|
 * | `version` | [major] | `1` |
 * | `version` | [minor] | `4` |
 * | `version` | [patch] | `0` |
 * | `version { rebuild }` | [rebuild] | `2` |
 *
 * @see VersionCodeScheme for the scheme that consumes these.
 */
class ConfigVersion(
    /** First number of `version`, so `1` in `1.4.0`. Range 0..999 in the default scheme. */
    val major: Int,
    /** Second number of `version`, so `4` in `1.4.0`. Range 0..999 in the default scheme. */
    val minor: Int,
    /** Third number of `version`, so `0` in `1.4.0`. Range 0..99 in the default scheme. */
    val patch: Int,
    /**
     * Counter for re-uploading the same version to a store.
     *
     * Comes from `version { rebuild = 1 }`, at the root or in the platform block
     * that is asking. Default: `0`. Range 0..9 in the default scheme.
     */
    val rebuild: Int,
) {
    /** Diagnostic form only, such as `1.4.0+2`. No store ever sees this text. */
    override fun toString(): String = "$major.$minor.$patch+$rebuild"
}

/**
 * Turns a release version into a build number.
 *
 * You write it once, as `version { scheme { ... } }`, and a platform block can
 * replace it for itself. The result is text, because the stores disagree about
 * what a build number looks like: Android wants one decimal integer, Apple
 * accepts up to three dot-separated numbers.
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"
 *     version {
 *         // 1.4.0 -> "1040000", 1.4.1 -> "1040100"
 *         scheme { v -> (1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.rebuild).toString() }
 *     }
 * }
 * ```
 *
 * Each platform validates the text it receives. Android parses it as an integer
 * and holds it to the store's range, and Google Play never forgets a number, so
 * a code can never shrink. Changing the scheme of a published app is safe only
 * when every new number beats every number already shipped.
 */
fun interface VersionCodeScheme {

    /** Returns the build number for [version], as the text the platform will receive. */
    fun compute(version: ConfigVersion): String
}

/**
 * The build-number schemes KiteConfig ships with.
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"    // no scheme { } block: DEFAULT is used
 * }
 * ```
 */
object VersionSchemes {

    /**
     * The scheme used when no `scheme` is set.
     *
     * Layout: `1 | major(3) | minor(3) | patch(2) | rebuild(1)`.
     *
     * ```
     * 1.4.0            ->  1001004000
     * 1.4.0 rebuild 1  ->  1001004001
     * 1.4.1            ->  1001004010
     * 1.5.0            ->  1001005000
     * ```
     *
     * Ten numbers per version. The ceiling is `1999999999`, comfortably under
     * Google Play's `2100000000` cap. A version bump always outranks every
     * rebuild of the version before it, so `rebuild` never needs resetting.
     *
     * Limits: `major` and `minor` 0..999, `patch` 0..99, `rebuild` 0..9. Going
     * over fails the build instead of producing a silently wrong number, and the
     * message shows you how to bring your own scheme.
     *
     * Need 100 rebuilds per version? Move a digit, at the cost of capping
     * `major` at 99:
     *
     * ```kotlin
     * // 1.4.0 -> "1010040000", 1.4.0 rebuild 42 -> "1010040042"
     * scheme { v ->
     *     fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
     *     "1" + pad(v.major, 2) + pad(v.minor, 3) + pad(v.patch, 2) + pad(v.rebuild, 2)
     * }
     * ```
     */
    val DEFAULT: VersionCodeScheme = VersionCodeScheme { version ->
        validateSchemeInput(version)
        "1" +
            version.major.padTo(3) +
            version.minor.padTo(3) +
            version.patch.padTo(2) +
            version.rebuild.padTo(1)
    }
}

/**
 * Check that [version] fits the digit budget of [VersionSchemes.DEFAULT].
 *
 * A scheme you wrote is never checked here. It owns its own layout, and only the
 * text it returns is checked, by the platform that receives it.
 */
internal fun validateSchemeInput(version: ConfigVersion) {
    requireSegment(version, "major", version.major, DEFAULT_MAX_MAJOR)
    requireSegment(version, "minor", version.minor, DEFAULT_MAX_MINOR)
    requireSegment(version, "patch", version.patch, DEFAULT_MAX_PATCH)
    requireSegment(version, "rebuild", version.rebuild, DEFAULT_MAX_REBUILD)
}

private fun requireSegment(version: ConfigVersion, property: String, value: Int, max: Int) {
    if (value in 0..max) return
    throw GradleException(
        "kiteConfig: the default build-number scheme cannot encode $property=$value " +
            "(version $version). Its layout allows $property in 0..$max. Supply your own with " +
            "version { scheme { v -> ... } }, or set the number yourself with " +
            "android { version { buildNumber = \"...\" } }." + segmentAdvice(property),
    )
}

private fun segmentAdvice(property: String): String = when (property) {
    "patch" -> " A three-digit patch needs a scheme that leaves room for it."
    "rebuild" -> " Bumping the version also frees the rebuild dial."
    else -> ""
}

private fun Int.padTo(width: Int): String = toString().padStart(width, '0')
