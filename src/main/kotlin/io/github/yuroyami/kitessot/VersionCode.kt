package io.github.yuroyami.kitessot

import org.gradle.api.GradleException

private val SEGMENT_RE = Regex("""0|[1-9]\d{0,2}""")

internal const val MAX_ANDROID_VERSION_CODE: Int = 2_100_000_000

/**
 * Derive an Android-style `versionCode` from a `versionName`:
 * `"1" + each dot segment zero-padded to 3 digits` (e.g. `0.3.0` → `1000003000`).
 *
 * Constraints that keep the result inside a signed 32-bit Int (Android's
 * `versionCode` is an `int`, and the Play Store ceiling is 2_100_000_000):
 *  - exactly three segments are required so the encoding width never changes,
 *  - every segment is `0` or a 1–3 digit number without leading zeroes
 *    (so the maximum derived value is `1999999999`).
 *
 * Anything else (a pre-release/build suffix like `1.2.3-rc1`, a `v` prefix, a
 * 4th segment, a segment > 999) throws a [GradleException] with actionable
 * guidance instead of the raw `NumberFormatException`/overflow the naive
 * formula used to produce. Callers who can't satisfy the constraint should set
 * `versionCodeOverride`.
 */
internal fun deriveVersionCode(versionName: String): Int {
    validateVersionName(versionName)
    val segments = versionName.split(".")
    if (segments.size != 3) {
        throw GradleException(
            "kiteSsot: cannot derive a monotonic versionCode from versionName " +
                "\"${diagnosticSafeText(versionName, 255)}\" — " +
                "exactly three numeric segments (x.y.z) are required. Short forms change the " +
                "encoded width and can make a later release's versionCode go backwards. Use " +
                "x.y.z or set kiteSsot { versionCodeOverride = <int> }."
        )
    }
    val offending = segments.firstOrNull { !SEGMENT_RE.matches(it) }
    if (offending != null) {
        throw GradleException(
            "kiteSsot: cannot derive a versionCode from versionName " +
                "\"${diagnosticSafeText(versionName, 255)}\" — segment " +
                "\"${diagnosticSafeText(offending, 32)}\" is not 0 or a 1–3 digit number without leading zeroes. " +
                "Use a numeric x.y.z " +
                "versionName, or set kiteSsot { versionCodeOverride = <int> }."
        )
    }
    return ("1" + segments.joinToString("") { it.padStart(3, '0') }).toInt()
}

/** Validate a Play-compatible Android version code and return it unchanged. */
internal fun validateVersionCode(value: Int, property: String = "versionCodeOverride"): Int {
    if (value !in 1..MAX_ANDROID_VERSION_CODE) {
        throw GradleException(
            "kiteSsot { $property } must be in 1..$MAX_ANDROID_VERSION_CODE " +
                "(the Google Play limit); got $value."
        )
    }
    return value
}

/** Validate the optional offline store baseline against a resolved next code. */
internal fun validatePublishedVersionCode(next: Int?, published: Int): Int {
    validateVersionCode(published, "android.publishedVersionCode")
    val candidate = next ?: throw GradleException(
        "kiteSsot { android { publishedVersionCode } } requires versionName or versionCodeOverride.",
    )
    validateVersionCode(candidate)
    if (candidate <= published) {
        throw GradleException(
            "kiteSsot resolved Android versionCode $candidate must be greater than the published " +
                "baseline $published. Increase versionName/versionCodeOverride before release.",
        )
    }
    return candidate
}
