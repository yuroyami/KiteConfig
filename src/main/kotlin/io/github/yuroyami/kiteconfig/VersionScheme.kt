package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException

private const val PLAY_VERSION_CODE_CEILING = 2_100_000_000

private const val DEFAULT_MAX_MAJOR = 999
private const val DEFAULT_MAX_MINOR = 999
private const val DEFAULT_MAX_PATCH = 99
private const val DEFAULT_MAX_REUPLOAD = 9

private const val LEGACY_SCHEME_SNIPPET =
    "formula { v -> (\"1\" + listOf(v.major, v.minor, v.patch)" +
        ".joinToString(\"\") { it.toString().padStart(3, '0') }).toInt() }"

/**
 * The four numbers KiteConfig hands to a build-number scheme.
 *
 * `version = "1.4.0"` splits into [major], [minor], and [patch]. [reupload] is the
 * extra dial you turn when a store burns an upload. A scheme reads all four and
 * returns one build number.
 *
 * ```kotlin
 * kiteConfig {
 *     version("1.4.0") {
 *         formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
 *     }
 * }
 * ```
 *
 * You never construct one. KiteConfig parses `version`, adds the platform `rebuild`
 * dial, and passes the result into your lambda.
 *
 * | From | Field | `1.4.0` with `rebuild = 2` |
 * |---|---|---|
 * | `version` | [major] | `1` |
 * | `version` | [minor] | `4` |
 * | `version` | [patch] | `0` |
 * | `android { rebuild }` or `ios { rebuild }` | [reupload] | `2` |
 *
 * @see VersionCodeScheme for the formula that consumes these.
 */
class ConfigVersion(
    /** First number of `version`, so `1` in `1.4.0`. Range 0..999 in the default scheme. */
    val major: Int,
    /** Second number of `version`, so `4` in `1.4.0`. Range 0..999 in the default scheme. */
    val minor: Int,
    /** Third number of `version`, so `0` in `1.4.0`. Range 0..99 in the default scheme. */
    val patch: Int,
    /**
     * Counter to re-upload the same version to a store. Feeds the formula.
     *
     * Comes from `android { rebuild = 1 }` or `ios { rebuild = 3 }`, whichever
     * platform is asking. Default: `0`. Range 0..9 in the default scheme.
     */
    val reupload: Int,
) {
    /** Diagnostic form only, such as `1.4.0+2`. No store ever sees this text. */
    override fun toString(): String = "$major.$minor.$patch+$reupload"
}

/**
 * The one formula that turns a version into a build number.
 *
 * You write it once, as `version("x") { formula { } }`. Android takes the result as
 * `versionCode`. Apple takes the same number, as text, for
 * `CURRENT_PROJECT_VERSION`. One number, two field types, no drift between the
 * platforms.
 *
 * The result must land in `1..2100000000`, the range Google Play accepts, and it
 * has to grow with every upload: Play compares codes as plain integers and never
 * forgets one. Apple is looser, but sharing one number keeps the platforms
 * honest with each other.
 *
 * ```kotlin
 * kiteConfig {
 *     version("1.4.0") {
 *         // 1.4.0 -> 1040000, 1.4.1 -> 1040100
 *         formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
 *     }
 * }
 * ```
 *
 * Write nothing and [VersionSchemes.DEFAULT] does the work. Both platforms can
 * still override the formula for themselves, and both can skip formulas entirely
 * by assigning `android { versionCode = ... }` or `ios { buildNumber = ... }`.
 *
 * Google Play compares codes as integers and remembers every upload, so a code can
 * never shrink. Changing the formula of a shipped app is safe only when the new
 * number is bigger than every number you have already published.
 */
fun interface VersionCodeScheme {

    /** Returns the build number for [version]. Must land in `1..2100000000`, the Play limit. */
    fun compute(version: ConfigVersion): Int
}

/**
 * The build-number formulas KiteConfig ships with.
 *
 * ```kotlin
 * kiteConfig {
 *     version = "1.4.0"    // no formula { } block: DEFAULT is used
 * }
 * ```
 */
object VersionSchemes {

    /**
     * The formula used when no `formula { }` is present.
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
     * Ten codes per version. The ceiling is `1999999999`, comfortably under Play's
     * `2100000000` cap. A version bump always outranks every rebuild of the version
     * before it, so `rebuild` never needs resetting.
     *
     * Limits: `major` and `minor` 0..999, `patch` 0..99, `rebuild` 0..9. Going over
     * fails the build instead of producing a silently wrong number, and the message
     * shows you how to bring your own formula.
     *
     * Need 100 rebuilds per version? Move a digit, at the cost of capping `major`
     * at 99:
     *
     * ```kotlin
     * // 1.4.0 -> 1010040000, 1.4.0 rebuild 42 -> 1010040042
     * formula { v ->
     *     fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
     *     "1${pad(v.major, 2)}${pad(v.minor, 3)}${pad(v.patch, 2)}${pad(v.reupload, 2)}".toInt()
     * }
     * ```
     */
    val DEFAULT: VersionCodeScheme = VersionCodeScheme { version ->
        validateSchemeInput(version)
        (
            "1" +
                version.major.padTo(3) +
                version.minor.padTo(3) +
                version.patch.padTo(2) +
                version.reupload.padTo(1)
            ).toInt()
    }
}

/**
 * Check that [version] fits the digit budget of [VersionSchemes.DEFAULT].
 *
 * Custom schemes are never checked here. They own their own layout, and only their
 * result is validated, by [validateResolvedVersionCode].
 */
internal fun validateSchemeInput(version: ConfigVersion) {
    requireSegment(version, "major", version.major, DEFAULT_MAX_MAJOR)
    requireSegment(version, "minor", version.minor, DEFAULT_MAX_MINOR)
    requireSegment(version, "patch", version.patch, DEFAULT_MAX_PATCH)
    // The label stays "rebuild": that is still the name of the DSL dial the reader can turn.
    requireSegment(version, "rebuild", version.reupload, DEFAULT_MAX_REUPLOAD)
}

/**
 * Check a resolved build number against the Google Play ceiling and return it
 * unchanged, so it can sit inside a provider chain.
 */
internal fun validateResolvedVersionCode(value: Int): Int {
    if (value !in 1..PLAY_VERSION_CODE_CEILING) {
        throw GradleException(
            "kiteConfig: the resolved build number $value is outside 1..$PLAY_VERSION_CODE_CEILING, " +
                "the Google Play versionCode limit. Fix the formula in " +
                "version(\"x\") { formula { v -> ... } }, or set the number yourself with " +
                "version(\"x\") { android { pin = ... } } or version(\"x\") { ios { pin = \"...\" } }.",
        )
    }
    return value
}

private fun requireSegment(version: ConfigVersion, property: String, value: Int, max: Int) {
    if (value in 0..max) return
    throw GradleException(
        "kiteConfig: the default build-number scheme cannot encode $property=$value " +
            "(version $version). Its layout allows $property in 0..$max. Supply your own formula " +
            "with version(\"x\") { formula { v -> ... } }, or set the number yourself with " +
            "version(\"x\") { android { pin = ... } } or version(\"x\") { ios { pin = \"...\" } }." +
            segmentAdvice(property),
    )
}

private fun segmentAdvice(property: String): String = when (property) {
    "patch" -> " The pre-3.0 formula fits as one line: $LEGACY_SCHEME_SNIPPET"
    "rebuild" -> " Bumping the version also frees the rebuild dial."
    else -> ""
}

private fun Int.padTo(width: Int): String = toString().padStart(width, '0')
