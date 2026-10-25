package io.github.yuroyami.kiteconfig.core

internal const val PLAY_VERSION_CODE_CEILING = 2_100_000_000

/** The DSL path that sets an exact build number, named per platform in error messages. */
@InternalKiteCoreApi
public fun buildNumberProperty(platform: String): String =
    "$platform { version { buildNumber = \"...\" } }"

internal const val MAX_ANDROID_VERSION_CODE: Int = PLAY_VERSION_CODE_CEILING

/** Validate a Play-compatible Android version code and return it unchanged. */
@InternalKiteCoreApi
public fun validateVersionCode(
    value: Int,
    property: String = buildNumberProperty("android"),
): Int {
    if (value !in 1..MAX_ANDROID_VERSION_CODE) {
        throw KiteConfigException(
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
@InternalKiteCoreApi
public fun validatePublishedVersionCode(next: Int?, published: String): Int {
    val baseline = published.trim().toIntOrNull()
        ?: throw KiteConfigException(
            "kiteConfig { android { version { publishedBuildNumber } } } must be one whole number, " +
                "the versionCode you last shipped; got \"${diagnosticSafeText(published, 64)}\".",
        )
    validateVersionCode(baseline, "android { version { publishedBuildNumber } }")
    val candidate = next ?: throw KiteConfigException(
        "kiteConfig { android { version { publishedBuildNumber } } } requires a version or an " +
            "explicit ${buildNumberProperty("android")}.",
    )
    validateVersionCode(candidate)
    if (candidate <= baseline) {
        throw KiteConfigException(
            "kiteConfig resolved Android versionCode $candidate must be greater than the published " +
                "baseline $baseline. Increase version, or bump android { version { rebuild } }, " +
                "before release.",
        )
    }
    return candidate
}
