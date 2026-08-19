package io.github.yuroyami.kitessot

import org.gradle.api.GradleException

private val VERSION_SEGMENT = Regex("""0|[1-9]\d*""")

/**
 * Split a `x.y.z` version string into the four numbers a scheme reads.
 *
 * The three segments must be plain non-negative integers without leading
 * zeroes, so a pre-release name like `1.2.3-rc1` is rejected with guidance
 * rather than a confusing parse error.
 */
internal fun parseSsotVersion(version: String, rebuild: Int, property: String): SsotVersion {
    val segments = version.trim().split('.')
    if (segments.size != 3 || segments.any { !VERSION_SEGMENT.matches(it) }) {
        throw GradleException(
            "kiteSsot: cannot derive a build number from version " +
                "\"${diagnosticSafeText(version, 255)}\". Three plain numeric segments (x.y.z) are " +
                "required, without leading zeroes or a pre-release suffix. Use a numeric version, or " +
                "set the number yourself with $property.",
        )
    }
    return SsotVersion(
        major = segments[0].toIntOrNull() ?: outOfRange(version, property),
        minor = segments[1].toIntOrNull() ?: outOfRange(version, property),
        patch = segments[2].toIntOrNull() ?: outOfRange(version, property),
        rebuild = rebuild,
    )
}

/**
 * Run [scheme] over a version string and validate the number it produced.
 *
 * [platform] names the block whose override the user should reach for when the
 * result does not fit, so the error is actionable instead of generic.
 */
internal fun computeVersionCode(
    scheme: VersionCodeScheme,
    version: String,
    rebuild: Int,
    platform: String,
): Int {
    val property = when (platform) {
        "ios" -> "ios { buildNumber = ... }"
        else -> "android { versionCode = ... }"
    }
    requireRebuild(rebuild, platform)
    val parsed = parseSsotVersion(version, rebuild, property)
    val computed = try {
        scheme.compute(parsed)
    } catch (failure: GradleException) {
        throw failure
    } catch (failure: Exception) {
        throw GradleException(
            "kiteSsot { scheme } failed on version $parsed: ${failure.message}. " +
                "Return a plain Int, or set the number yourself with $property.",
            failure,
        )
    }
    return validateResolvedVersionCode(computed)
}

private fun requireRebuild(rebuild: Int, platform: String) {
    if (rebuild >= 0) return
    throw GradleException("kiteSsot { $platform { rebuild } } must be zero or greater; got $rebuild.")
}

private fun outOfRange(version: String, property: String): Nothing = throw GradleException(
    "kiteSsot: version \"${diagnosticSafeText(version, 255)}\" has a segment too large to encode. " +
        "Set the number yourself with $property.",
)
