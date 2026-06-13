package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException

private val SEGMENT_RE = Regex("""\d{1,3}""")

/**
 * Derive an Android-style `versionCode` from a `versionName`:
 * `"1" + each dot segment zero-padded to 3 digits` (e.g. `0.3.0` → `1000003000`).
 *
 * Constraints that keep the result inside a signed 32-bit Int (Android's
 * `versionCode` is an `int`, and the Play Store ceiling is 2_100_000_000):
 *  - every segment must be a 1–3 digit number (so the max is `1999999999`),
 *  - at most 3 segments.
 *
 * Anything else (a pre-release/build suffix like `1.2.3-rc1`, a `v` prefix, a
 * 4th segment, a segment > 999) throws a [GradleException] with actionable
 * guidance instead of the raw `NumberFormatException`/overflow the naive
 * formula used to produce. Callers who can't satisfy the constraint should set
 * `versionCodeOverride`.
 */
internal fun deriveVersionCode(versionName: String): Int {
    val segments = versionName.split(".")
    val offending = segments.firstOrNull { !SEGMENT_RE.matches(it) }
    if (offending != null) {
        throw GradleException(
            "kmpSsot: cannot derive a versionCode from versionName \"$versionName\" — " +
                "segment \"$offending\" is not a 1–3 digit number. Use a numeric x.y.z " +
                "versionName, or set kmpSsot { versionCodeOverride = <int> }."
        )
    }
    if (segments.size > 3) {
        throw GradleException(
            "kmpSsot: cannot derive a versionCode from versionName \"$versionName\" — " +
                "${segments.size} segments exceed the 3 that fit in a 32-bit versionCode. " +
                "Use x.y.z, or set kmpSsot { versionCodeOverride = <int> }."
        )
    }
    return ("1" + segments.joinToString("") { it.padStart(3, '0') }).toInt()
}
