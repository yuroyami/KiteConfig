package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.diagnosticSafeText
import org.gradle.api.GradleException

private val GRADLE_PROJECT_PATH = Regex(""":(?:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*)?""")
private const val MAX_GRADLE_PROJECT_PATH_CHARS = 1_024

internal fun validateGradleProjectPath(value: String, property: String): String {
    if (value.length > MAX_GRADLE_PROJECT_PATH_CHARS || !GRADLE_PROJECT_PATH.matches(value)) {
        throw GradleException(
            "kiteConfig { $property } \"${diagnosticSafeText(value, 256)}\" is not a valid absolute " +
                "Gradle project path (for example :shared).",
        )
    }
    return value
}

/**
 * Parse a `-P` switch that guards destructive work.
 *
 * `String.toBoolean()` answers `false` for everything that is not `"true"`, so
 * `-Pkiteconfig.dryRun=treu` would quietly turn a requested preview into a real
 * source rewrite, and `-Pkiteconfig.backups=treu` would turn backups off. A flag
 * whose whole job is protection has to refuse what it does not understand.
 */
internal fun strictBooleanProperty(property: String, raw: String): Boolean =
    when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw GradleException(
            "kiteConfig -P$property must be exactly 'true' or 'false'; got '$raw'. " +
                "This switch guards source mutation, so an unrecognised value is refused " +
                "instead of being treated as 'false'.",
        )
    }
