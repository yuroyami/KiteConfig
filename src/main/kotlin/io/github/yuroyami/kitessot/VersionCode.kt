package io.github.yuroyami.kitessot

import org.gradle.api.GradleException

internal const val MAX_ANDROID_VERSION_CODE: Int = 2_100_000_000

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
