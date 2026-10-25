package io.github.yuroyami.kiteconfig.toolchain

import io.github.yuroyami.kiteconfig.core.defaultBuildNumber

/** The build number for [version]: [buildNumber] when set, otherwise the default layout. */
internal fun resolveBuildNumber(version: String, rebuild: Int, buildNumber: String?): String {
    if (buildNumber != null) return buildNumber.trim()
    val (major, minor, patch) = version.trim().split('.').map(String::toInt)
    return defaultBuildNumber(major, minor, patch, rebuild)
}

/** The build number as the single whole number that Android needs. */
internal fun androidVersionCode(buildNumber: String): Int = buildNumber.toInt()
