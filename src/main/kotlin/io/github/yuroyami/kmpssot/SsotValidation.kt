package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException

private val ANDROID_ID_SEGMENT = Regex("""[A-Za-z][A-Za-z0-9_]*""")
private val APPLE_ID_SEGMENT = Regex("""[A-Za-z0-9][A-Za-z0-9-]*""")
private val APPLE_MARKETING_VERSION = Regex("""(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)""")
private val GRADLE_PROJECT_PATH = Regex(""":(?:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*)?""")
private val QUALIFIED_KOTLIN_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+""")
private val WINDOWS_ABSOLUTE_PATH = Regex("""^[A-Za-z]:[\\/].*""")
private val CANONICAL_NONNEGATIVE_INTEGER = Regex("(?:0|[1-9]\\d*)")
private const val MAX_VERSION_NAME_CHARS = 255
private const val MAX_APPLE_MARKETING_VERSION_CHARS = 64
private const val MAX_APPLE_BUILD_NUMBER_CHARS = 10
private const val MAX_DEPLOYMENT_TARGET_CHARS = 64
private const val MAX_GRADLE_PROJECT_PATH_CHARS = 1_024
private const val MAX_RELATIVE_PROJECT_PATH_CHARS = 4_096
private const val MAX_NDK_VERSION_CHARS = 64
private const val MAX_OPT_IN_MARKER_CHARS = 512
private const val MAX_ANDROID_SDK_LEVEL = 10_000

internal fun validateAppName(value: String): String {
    if (value.isBlank() || value.length > 255 || value.any(Char::isISOControl)) {
        throw GradleException("kmpSsot { appName } must be non-blank, at most 255 characters, and contain no controls.")
    }
    return value
}

internal fun validateAndroidApplicationId(value: String): String {
    val segments = value.split('.')
    if (value.length > 255 || segments.size < 2 || segments.any { !ANDROID_ID_SEGMENT.matches(it) }) {
        throw GradleException(
            "kmpSsot resolved Android applicationId \"${diagnosticSafeText(value, 255)}\" is invalid. Use at least two " +
                "dot-separated segments, each beginning with a letter."
        )
    }
    return value
}

internal fun validateAppleBundleId(value: String): String {
    val segments = value.split('.')
    if (value.length > 255 || segments.size < 2 || segments.any { !APPLE_ID_SEGMENT.matches(it) }) {
        throw GradleException(
            "kmpSsot resolved Apple bundle identifier \"${diagnosticSafeText(value, 255)}\" is invalid. Use reverse-DNS " +
                "segments containing letters, digits, or hyphens."
        )
    }
    return value
}

/** Validate the platform-neutral display version before any adapter consumes it. */
internal fun validateVersionName(value: String): String {
    if (value.isBlank() || value.length > MAX_VERSION_NAME_CHARS || value.any(Char::isISOControl)) {
        throw GradleException(
            "kmpSsot { versionName } must be non-blank, at most $MAX_VERSION_NAME_CHARS characters, " +
                "and contain no controls.",
        )
    }
    return value
}

internal fun validateAppleMarketingVersion(value: String): String {
    if (value.length > MAX_APPLE_MARKETING_VERSION_CHARS ||
        !APPLE_MARKETING_VERSION.matches(value) || value.split('.').any { it.toLongOrNull() == null }
    ) {
        throw GradleException(
            "kmpSsot Apple marketing version \"${diagnosticSafeText(value, 64)}\" is invalid. " +
                "CFBundleShortVersionString requires three non-negative integer components (x.y.z)."
        )
    }
    return value
}

internal fun validateAppleBuildNumber(value: String): String {
    if (!isValidAppleBuildNumber(value)) {
        throw GradleException(
            "kmpSsot { iosBuildNumber } \"${diagnosticSafeText(value, 32)}\" is invalid. " +
                "CFBundleVersion requires one to three " +
                "numeric components: a positive first component of at most four digits, followed by " +
                "optional components of at most two digits each."
        )
    }
    return value
}

internal fun validateUniversalAppIconDeploymentTarget(value: String): String {
    if (value.length > MAX_DEPLOYMENT_TARGET_CHARS) {
        throw GradleException(
            "kmpSsot { ios { deploymentTarget } } is longer than " +
                "$MAX_DEPLOYMENT_TARGET_CHARS characters.",
        )
    }
    val components = value.split('.')
    val valid = components.size in 1..3 && components.all { component ->
        CANONICAL_NONNEGATIVE_INTEGER.matches(component) && component.toLongOrNull() != null
    }
    if (!valid) {
        throw GradleException(
            "kmpSsot { ios { deploymentTarget } } \"${diagnosticSafeText(value, 64)}\" is invalid. Use a numeric Apple " +
                "deployment target such as 12.0."
        )
    }
    if (components.first().toLong() < 12L) {
        throw GradleException(
            "kmpSsot's single-size universal AppIcon requires ios.deploymentTarget >= 12.0; got $value."
        )
    }
    return value
}

internal fun isValidAppleBuildNumber(value: String): Boolean {
    if (value.length > MAX_APPLE_BUILD_NUMBER_CHARS) return false
    val components = value.split('.')
    if (components.size !in 1..3 || components.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) {
        return false
    }
    if (components[0].length > 4 || components[0].all { it == '0' }) return false
    return components.drop(1).all { it.length <= 2 }
}

internal fun validateGradleProjectPath(value: String, property: String): String {
    if (value.length > MAX_GRADLE_PROJECT_PATH_CHARS || !GRADLE_PROJECT_PATH.matches(value)) {
        throw GradleException(
            "kmpSsot { $property } \"${diagnosticSafeText(value, 256)}\" is not a valid absolute " +
                "Gradle project path (for example :shared).",
        )
    }
    return value
}

/**
 * Validate a user-configured path that will be resolved below the root project.
 * Both separator styles are checked so a configuration authored on one OS
 * cannot become an escape when consumed on another.
 */
internal fun validateRelativeProjectPath(value: String, property: String): String {
    if (value.length > MAX_RELATIVE_PROJECT_PATH_CHARS) {
        throw GradleException(
            "kmpSsot { $property } exceeds the $MAX_RELATIVE_PROJECT_PATH_CHARS-character path limit.",
        )
    }
    val segments = value.split('/', '\\')
    val invalid = value.isBlank() || value.any(Char::isISOControl) ||
        value.startsWith('/') || value.startsWith('\\') || WINDOWS_ABSOLUTE_PATH.matches(value) ||
        segments.any { it.isEmpty() || it == "." || it == ".." }
    if (invalid) {
        throw GradleException(
            "kmpSsot { $property } must be a non-empty path contained by the root project; " +
                "absolute paths, empty segments, '.' and '..' are not allowed (got " +
                "\"${diagnosticSafeText(value, 256)}\")."
        )
    }
    return value
}

internal fun validateSdkLevels(compileSdk: Int?, minSdk: Int?, targetSdk: Int?) {
    listOf("compileSdk" to compileSdk, "minSdk" to minSdk, "targetSdk" to targetSdk).forEach { (name, value) ->
        if (value != null && value !in 1..MAX_ANDROID_SDK_LEVEL) {
            throw GradleException(
                "kmpSsot { android { $name } } must be in 1..$MAX_ANDROID_SDK_LEVEL; got $value.",
            )
        }
    }
    if (compileSdk != null && minSdk != null && minSdk > compileSdk) {
        throw GradleException("kmpSsot Android SDK levels are inconsistent: minSdk ($minSdk) exceeds compileSdk ($compileSdk).")
    }
    if (compileSdk != null && targetSdk != null && targetSdk > compileSdk) {
        throw GradleException("kmpSsot Android SDK levels are inconsistent: targetSdk ($targetSdk) exceeds compileSdk ($compileSdk).")
    }
    if (minSdk != null && targetSdk != null && minSdk > targetSdk) {
        throw GradleException("kmpSsot Android SDK levels are inconsistent: minSdk ($minSdk) exceeds targetSdk ($targetSdk).")
    }
}

internal fun validateJavaVersion(value: Int): Int {
    if (value !in 8..26) {
        throw GradleException("kmpSsot { javaVersion } must be a supported Java language level in 8..26; got $value.")
    }
    return value
}

/** Validate an adaptive-icon foreground fraction that remains inside its canvas. */
internal fun validateLogoSafeZoneRatio(value: Double): Double {
    if (!value.isFinite() || value <= 0.0 || value > 1.0) {
        throw GradleException(
            "kmpSsot { appLogoAndroidSafeZoneRatio } must be finite and in (0, 1]; got $value.",
        )
    }
    return value
}

internal fun validateNdkVersion(value: String): String {
    val components = if (value.length <= MAX_NDK_VERSION_CHARS) value.split('.') else emptyList()
    val valid = components.size in 3..4 && components.all { component ->
        CANONICAL_NONNEGATIVE_INTEGER.matches(component) && component.toLongOrNull() != null
    }
    if (!valid) {
        throw GradleException(
            "kmpSsot { android { ndkVersion } } must contain three or four bounded numeric " +
                "components; got \"${diagnosticSafeText(value, 64)}\"."
        )
    }
    return value
}

internal fun validateOptInMarker(value: String): String {
    if (value.length > MAX_OPT_IN_MARKER_CHARS ||
        !QUALIFIED_KOTLIN_NAME.matches(value) || value.split('.').any { it == "_" }
    ) {
        throw GradleException(
            "kmpSsot extra opt-in marker \"${diagnosticSafeText(value, 256)}\" is not a bounded, " +
                "qualified Kotlin declaration name.",
        )
    }
    return value
}
