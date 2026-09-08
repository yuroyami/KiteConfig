package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException

internal const val MAX_ANDROID_VERSION_CODE: Int = PLAY_VERSION_CODE_CEILING

/** Validate a Play-compatible Android version code and return it unchanged. */
internal fun validateVersionCode(
    value: Int,
    property: String = buildNumberProperty("android"),
): Int {
    if (value !in 1..MAX_ANDROID_VERSION_CODE) {
        throw GradleException(
            "kiteConfig { $property } must be in 1..$MAX_ANDROID_VERSION_CODE " +
                "(the Google Play limit); got $value.",
        )
    }
    return value
}

/**
 * Validate the Android publication baseline against the resolved next code.
 *
 * Publication histories are per platform, so this compares Android's own
 * numbers only. Google Play compares codes as integers and never forgets one,
 * so the next code has to beat the baseline rather than match it.
 */
internal fun validatePublishedVersionCode(next: Int?, published: String): Int {
    val baseline = published.trim().toIntOrNull()
        ?: throw GradleException(
            "kiteConfig { android { version { publishedBuildNumber } } } must be one whole number, " +
                "the versionCode you last shipped; got \"${diagnosticSafeText(published, 64)}\".",
        )
    validateVersionCode(baseline, "android { version { publishedBuildNumber } }")
    val candidate = next ?: throw GradleException(
        "kiteConfig { android { version { publishedBuildNumber } } } requires a version or an " +
            "explicit ${buildNumberProperty("android")}.",
    )
    validateVersionCode(candidate)
    if (candidate <= baseline) {
        throw GradleException(
            "kiteConfig resolved Android versionCode $candidate must be greater than the published " +
                "baseline $baseline. Increase version, or bump android { version { rebuild } }, " +
                "before release.",
        )
    }
    return candidate
}
