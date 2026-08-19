package io.github.yuroyami.kitessot

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
private const val MAX_APPLE_BUILD_NUMBER_CHARS = 32
private const val MAX_APPLE_BUILD_COMPONENT_DIGITS = 10
private const val MAX_DEPLOYMENT_TARGET_CHARS = 64
private const val MAX_GRADLE_PROJECT_PATH_CHARS = 1_024
private const val MAX_RELATIVE_PROJECT_PATH_CHARS = 4_096
private const val MAX_NDK_VERSION_CHARS = 64
private const val MAX_OPT_IN_MARKER_CHARS = 512
private const val MAX_ANDROID_SDK_LEVEL = 10_000

internal fun validateAppName(value: String): String {
    if (value.isBlank() || value.length > 255 || value.any(Char::isISOControl)) {
        throw GradleException("kiteSsot { appName } must be non-blank, at most 255 characters, and contain no controls.")
    }
    return value
}

internal fun validateAndroidApplicationId(value: String): String {
    val segments = value.split('.')
    if (value.length > 255 || segments.size < 2 || segments.any { !ANDROID_ID_SEGMENT.matches(it) }) {
        throw GradleException(
            "kiteSsot resolved Android applicationId \"${diagnosticSafeText(value, 255)}\" is invalid. Use at least two " +
                "dot-separated segments, each beginning with a letter."
        )
    }
    return value
}

internal fun validateAppleBundleId(value: String): String {
    val segments = value.split('.')
    if (value.length > 255 || segments.size < 2 || segments.any { !APPLE_ID_SEGMENT.matches(it) }) {
        throw GradleException(
            "kiteSsot resolved Apple bundle identifier \"${diagnosticSafeText(value, 255)}\" is invalid. Use reverse-DNS " +
                "segments containing letters, digits, or hyphens."
        )
    }
    return value
}

/** Validate the platform-neutral display version before any adapter consumes it. */
internal fun validateVersionName(value: String): String {
    if (value.isBlank() || value.length > MAX_VERSION_NAME_CHARS || value.any(Char::isISOControl)) {
        throw GradleException(
            "kiteSsot { version } must be non-blank, at most $MAX_VERSION_NAME_CHARS characters, " +
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
            "kiteSsot Apple marketing version \"${diagnosticSafeText(value, 64)}\" is invalid. " +
                "CFBundleShortVersionString requires three non-negative integer components (x.y.z)."
        )
    }
    return value
}

internal fun validateAppleBuildNumber(value: String): String {
    if (!isValidAppleBuildNumber(value)) {
        throw GradleException(
            "kiteSsot { ios { buildNumber } } \"${diagnosticSafeText(value, 32)}\" is invalid. " +
                "CFBundleVersion requires one to three numeric components, each at most " +
                "$MAX_APPLE_BUILD_COMPONENT_DIGITS digits, and a first component that is not zero."
        )
    }
    return value
}

internal fun validateUniversalAppIconDeploymentTarget(value: String): String {
    if (value.length > MAX_DEPLOYMENT_TARGET_CHARS) {
        throw GradleException(
            "kiteSsot { ios { deploymentTarget } } is longer than " +
                "$MAX_DEPLOYMENT_TARGET_CHARS characters.",
        )
    }
    val components = value.split('.')
    val valid = components.size in 1..3 && components.all { component ->
        CANONICAL_NONNEGATIVE_INTEGER.matches(component) && component.toLongOrNull() != null
    }
    if (!valid) {
        throw GradleException(
            "kiteSsot { ios { deploymentTarget } } \"${diagnosticSafeText(value, 64)}\" is invalid. Use a numeric Apple " +
                "deployment target such as 12.0."
        )
    }
    if (components.first().toLong() < 12L) {
        throw GradleException(
            "kiteSsot's single-size universal AppIcon requires ios.deploymentTarget >= 12.0; got $value."
        )
    }
    return value
}

/**
 * Whether [value] is a usable `CFBundleVersion`.
 *
 * Apple asks for one to three period-separated integers, and compares them
 * componentwise. KiteSSOT deliberately does NOT narrow that further: an earlier
 * four-digit cap on the first component was this plugin being cautious rather
 * than App Store enforcement, and it would reject the ten-digit ordinal that the
 * shared build-number scheme produces.
 *
 * The only real rules kept here: numeric components, at most three of them, each
 * short enough to stay an integer, and a first component that is not all zeros
 * (Apple treats build 0 as absent, and it would collide with an unsuffixed
 * upload).
 */
internal fun isValidAppleBuildNumber(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_APPLE_BUILD_NUMBER_CHARS) return false
    val components = value.split('.')
    if (components.size !in 1..3) return false
    if (components.any {
            it.isEmpty() ||
                it.length > MAX_APPLE_BUILD_COMPONENT_DIGITS ||
                it.any { c -> !c.isDigit() } ||
                it.toLongOrNull() == null
        }
    ) {
        return false
    }
    return !components[0].all { it == '0' }
}

/**
 * Componentwise compare two `CFBundleVersion` strings the way App Store Connect
 * does: numerically, component by component, with a missing trailing
 * component read as `0`. So `"2.1"` outranks `"2"`, and `"2.0"` equals `"2"`.
 */
internal fun compareAppleBuildNumbers(a: String, b: String): Int {
    val left = a.split('.').map { it.toLong() }
    val right = b.split('.').map { it.toLong() }
    for (i in 0 until maxOf(left.size, right.size)) {
        val cmp = (left.getOrElse(i) { 0L }).compareTo(right.getOrElse(i) { 0L })
        if (cmp != 0) return cmp
    }
    return 0
}

/** Validate the optional offline TestFlight baseline against a resolved next build number. */
internal fun validatePublishedBuildNumber(next: String?, published: String): String {
    if (!isValidAppleBuildNumber(published)) {
        throw GradleException(
            "kiteSsot { ios { publishedBuildNumber } } \"${diagnosticSafeText(published, 32)}\" is invalid. " +
                "CFBundleVersion requires one to three numeric components, each at most " +
                "$MAX_APPLE_BUILD_COMPONENT_DIGITS digits, and a first component that is not zero.",
        )
    }
    val candidate = next ?: throw GradleException(
        "kiteSsot { ios { publishedBuildNumber } } requires a resolvable ios { buildNumber }.",
    )
    if (!isValidAppleBuildNumber(candidate)) {
        throw GradleException(
            "kiteSsot resolved Apple build number \"${diagnosticSafeText(candidate, 32)}\" is invalid.",
        )
    }
    if (compareAppleBuildNumbers(candidate, published) <= 0) {
        throw GradleException(
            "kiteSsot resolved Apple build number \"$candidate\" must be greater than the published " +
                "baseline \"$published\". Bump ios { rebuild }, or set ios { buildNumber } explicitly, " +
                "before release.",
        )
    }
    return candidate
}

internal fun validateGradleProjectPath(value: String, property: String): String {
    if (value.length > MAX_GRADLE_PROJECT_PATH_CHARS || !GRADLE_PROJECT_PATH.matches(value)) {
        throw GradleException(
            "kiteSsot { $property } \"${diagnosticSafeText(value, 256)}\" is not a valid absolute " +
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
            "kiteSsot { $property } exceeds the $MAX_RELATIVE_PROJECT_PATH_CHARS-character path limit.",
        )
    }
    val segments = value.split('/', '\\')
    val invalid = value.isBlank() || value.any(Char::isISOControl) ||
        value.startsWith('/') || value.startsWith('\\') || WINDOWS_ABSOLUTE_PATH.matches(value) ||
        segments.any { it.isEmpty() || it == "." || it == ".." }
    if (invalid) {
        throw GradleException(
            "kiteSsot { $property } must be a non-empty path contained by the root project; " +
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
                "kiteSsot { android { $name } } must be in 1..$MAX_ANDROID_SDK_LEVEL; got $value.",
            )
        }
    }
    if (compileSdk != null && minSdk != null && minSdk > compileSdk) {
        throw GradleException("kiteSsot Android SDK levels are inconsistent: minSdk ($minSdk) exceeds compileSdk ($compileSdk).")
    }
    if (compileSdk != null && targetSdk != null && targetSdk > compileSdk) {
        throw GradleException("kiteSsot Android SDK levels are inconsistent: targetSdk ($targetSdk) exceeds compileSdk ($compileSdk).")
    }
    if (minSdk != null && targetSdk != null && minSdk > targetSdk) {
        throw GradleException("kiteSsot Android SDK levels are inconsistent: minSdk ($minSdk) exceeds targetSdk ($targetSdk).")
    }
}

internal fun validateJavaVersion(value: Int): Int {
    if (value !in 8..26) {
        throw GradleException("kiteSsot { jvmTarget } must be a supported Java language level in 8..26; got $value.")
    }
    return value
}

/** Validate an adaptive-icon foreground fraction that remains inside its canvas. */
internal fun validateLogoSafeZoneRatio(value: Double): Double {
    if (!value.isFinite() || value <= 0.0 || value > 1.0) {
        throw GradleException(
            "kiteSsot { logo { androidSafeZone } } must be finite and in (0, 1]; got $value.",
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
            "kiteSsot { android { ndkVersion } } must contain three or four bounded numeric " +
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
            "kiteSsot extra opt-in marker \"${diagnosticSafeText(value, 256)}\" is not a bounded, " +
                "qualified Kotlin declaration name.",
        )
    }
    return value
}

/**
 * Parse a `-P` switch that guards destructive work.
 *
 * `String.toBoolean()` answers `false` for everything that is not `"true"`, so
 * `-Pkitessot.dryRun=treu` would quietly turn a requested preview into a real
 * source rewrite, and `-Pkitessot.backups=treu` would turn backups off. A flag
 * whose whole job is protection has to refuse what it does not understand.
 */
internal fun strictBooleanProperty(property: String, raw: String): Boolean =
    when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw GradleException(
            "kiteSsot -P$property must be exactly 'true' or 'false'; got '$raw'. " +
                "This switch guards source mutation, so an unrecognised value is refused " +
                "instead of being treated as 'false'.",
        )
    }
