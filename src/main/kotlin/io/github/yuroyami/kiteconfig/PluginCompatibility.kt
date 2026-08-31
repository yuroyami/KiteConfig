package io.github.yuroyami.kiteconfig

internal data class ToolVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ToolVersion> {
    override fun compareTo(other: ToolVersion): Int =
        compareValuesBy(this, other, ToolVersion::major, ToolVersion::minor, ToolVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

private val LEADING_VERSION = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?.*$""")
private val STABLE_TOOL_VERSION = Regex(
    """^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$""",
)
private val STABLE_KGP_RUNTIME_VERSION = Regex(
    """^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-release-(?:0|[1-9]\d*))?$""",
)
private val COMPOSE_RUNTIME_VERSION = Regex(
    """^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:alpha|beta|rc|dev)[0-9.]*)?$""",
)

internal fun parseToolVersion(value: String): ToolVersion? {
    val match = LEADING_VERSION.matchEntire(value.trim()) ?: return null
    return ToolVersion(
        match.groupValues[1].toIntOrNull() ?: return null,
        match.groupValues[2].toIntOrNull() ?: return null,
        match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null,
    )
}

/** Evidence-backed range compiled and exercised by this release line. */
internal fun isSupportedAgpVersion(value: String): Boolean {
    if (!STABLE_TOOL_VERSION.matches(value.trim())) return false
    val version = parseToolVersion(value) ?: return false
    return version >= ToolVersion(8, 5, 2) && version < ToolVersion(9, 4, 0)
}

/** KGP adapters are intentionally narrow until the CI matrix proves another line. */
internal fun isSupportedKgpVersion(value: String): Boolean {
    // Stable KGP artifacts expose Maven version 2.4.0 but package
    // Implementation-Version 2.4.0-release-<build>. Accept that exact JetBrains
    // stable-build form while continuing to reject RC, Beta, dev, and arbitrary
    // suffixes.
    if (!STABLE_KGP_RUNTIME_VERSION.matches(value.trim())) return false
    val version = parseToolVersion(value) ?: return false
    return version >= ToolVersion(2, 4, 0) && version < ToolVersion(2, 5, 0)
}

/**
 * Compose ships long alpha, beta and rc lines that real projects build on, so
 * unlike the AGP rule this one accepts a pre-release suffix and compares only
 * the numeric part.
 */
internal fun isSupportedComposeVersion(value: String): Boolean {
    if (!COMPOSE_RUNTIME_VERSION.matches(value.trim())) return false
    val version = parseToolVersion(value) ?: return false
    return version >= ToolVersion(1, 11, 0) && version < ToolVersion(1, 13, 0)
}
