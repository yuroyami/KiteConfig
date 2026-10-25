package io.github.yuroyami.kiteconfig.toolchain

import io.github.yuroyami.kiteconfig.core.defaultBuildNumber

private val VERSION_SEGMENT = Regex("""0|[1-9]\d*""")

/** Google Play refuses a larger versionCode. */
private const val PLAY_VERSION_CODE_CEILING = 2_100_000_000

/** The limits of the default layout, the same as in the Gradle plugin. */
private val SEGMENT_LIMITS = listOf("major" to 999, "minor" to 999, "patch" to 99)
private const val MAX_REBUILD = 9

/** The build number for [version]: [buildNumber] when set, otherwise the default layout. */
internal fun resolveBuildNumber(version: String, rebuild: Int, buildNumber: String?): String {
    require(rebuild >= 0) { "plugins: kiteconfig: rebuild must be zero or greater; got $rebuild." }
    if (buildNumber != null) {
        require(buildNumber.isNotBlank()) { "plugins: kiteconfig: buildNumber must not be blank." }
        return buildNumber.trim()
    }
    val segments = version.trim().split('.')
    require(segments.size == 3 && segments.all(VERSION_SEGMENT::matches)) {
        "plugins: kiteconfig: version \"$version\" needs three plain numbers (x.y.z), without " +
            "leading zeroes or a pre-release suffix. Use a numeric version, or set " +
            "plugins: kiteconfig: buildNumber."
    }
    val numbers = segments.map { it.toIntOrNull() ?: Int.MAX_VALUE }
    SEGMENT_LIMITS.zip(numbers).forEach { (limit, value) ->
        val (name, max) = limit
        require(value <= max) {
            "plugins: kiteconfig: version \"$version\" has $name=$value, but the default build-number " +
                "layout allows $name in 0..$max. Set plugins: kiteconfig: buildNumber instead."
        }
    }
    require(rebuild <= MAX_REBUILD) {
        "plugins: kiteconfig: rebuild=$rebuild does not fit the default build-number layout, which " +
            "allows rebuild in 0..$MAX_REBUILD. Bump the version, or set plugins: kiteconfig: buildNumber."
    }
    return defaultBuildNumber(numbers[0], numbers[1], numbers[2], rebuild)
}

/** The build number as the single whole number that Android needs. */
internal fun androidVersionCode(buildNumber: String): Int {
    val code = requireNotNull(buildNumber.toIntOrNull()) {
        "Android needs the build number as one whole number, but it is \"$buildNumber\". " +
            "Set plugins: kiteconfig: buildNumber to a whole number."
    }
    require(code in 1..PLAY_VERSION_CODE_CEILING) {
        "The Android versionCode $code must be in 1..$PLAY_VERSION_CODE_CEILING (the Google Play limit)."
    }
    return code
}
