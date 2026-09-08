package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException

private val VERSION_SEGMENT = Regex("""0|[1-9]\d*""")

/** The DSL path that sets an exact build number, named per platform in error messages. */
internal fun buildNumberProperty(platform: String): String =
    "$platform { version { buildNumber = \"...\" } }"

/**
 * Split a `x.y.z` version string into the four numbers a scheme reads.
 *
 * The three segments must be plain non-negative integers without leading
 * zeroes, so a pre-release name like `1.2.3-rc1` is rejected with guidance
 * rather than a confusing parse error.
 */
internal fun parseConfigVersion(version: String, rebuild: Int, property: String): ConfigVersion {
    val segments = version.trim().split('.')
    if (segments.size != 3 || segments.any { !VERSION_SEGMENT.matches(it) }) {
        throw GradleException(
            "kiteConfig: cannot derive a build number from version " +
                "\"${diagnosticSafeText(version, 255)}\". Three plain numeric segments (x.y.z) are " +
                "required, without leading zeroes or a pre-release suffix. Use a numeric version, or " +
                "set the number yourself with $property.",
        )
    }
    return ConfigVersion(
        major = segments[0].toIntOrNull() ?: outOfRange(version, property),
        minor = segments[1].toIntOrNull() ?: outOfRange(version, property),
        patch = segments[2].toIntOrNull() ?: outOfRange(version, property),
        rebuild = rebuild,
    )
}

/**
 * Run [scheme] over a version string and return the build-number text it produced.
 *
 * [platform] names the block whose override the user should reach for when the
 * result does not suit, so the error is actionable instead of generic. The text
 * is not checked here; each platform holds it to its own store's rules.
 */
internal fun computeBuildNumber(
    scheme: VersionCodeScheme,
    version: String,
    rebuild: Int,
    platform: String,
): String {
    val property = buildNumberProperty(platform)
    requireRebuild(rebuild, platform)
    val parsed = parseConfigVersion(version, rebuild, property)
    val computed = try {
        scheme.compute(parsed)
    } catch (failure: GradleException) {
        throw failure
    } catch (failure: Exception) {
        throw GradleException(
            "kiteConfig { version { scheme } } failed on version $parsed: ${failure.message}. " +
                "Return a String, or set the number yourself with $property.",
            failure,
        )
    }
    if (computed.isBlank()) {
        throw GradleException(
            "kiteConfig { version { scheme } } returned an empty build number for version $parsed. " +
                "Return a non-empty String, or set the number yourself with $property.",
        )
    }
    return computed
}

/**
 * Read a build number as the single decimal integer Android requires.
 *
 * Apple accepts dotted build numbers, so the shared contract is text. Android is
 * the platform that has to narrow it, and it says so when the text will not fit.
 */
internal fun androidVersionCodeFrom(text: String): Int {
    val trimmed = text.trim()
    val parsed = trimmed.toIntOrNull()
        ?: throw GradleException(
            "kiteConfig: Android needs its versionCode to be one whole number, and the build " +
                "number resolved to \"${diagnosticSafeText(trimmed, 64)}\". Give Android its own " +
                "number with ${buildNumberProperty("android")}, or return a plain integer from " +
                "version { scheme { v -> ... } }.",
        )
    return validateVersionCode(parsed)
}

private fun requireRebuild(rebuild: Int, platform: String) {
    if (rebuild >= 0) return
    throw GradleException("kiteConfig { $platform { version { rebuild } } } must be zero or greater; got $rebuild.")
}

private fun outOfRange(version: String, property: String): Nothing = throw GradleException(
    "kiteConfig: version \"${diagnosticSafeText(version, 255)}\" has a segment too large to encode. " +
        "Set the number yourself with $property.",
)
