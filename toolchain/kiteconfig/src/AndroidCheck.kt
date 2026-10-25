package io.github.yuroyami.kiteconfig.toolchain

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.TaskAction

/**
 * Fails when the Android settings in `module.yaml` disagree with `plugins: kiteconfig:`.
 *
 * A plugin cannot set module settings, so the failure names the lines to write instead.
 * An unset setting arrives as its toolchain default (`versionCode` 1, `versionName` "unspecified").
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkAndroidSettings(
    checkSettings: Boolean,
    version: String,
    rebuild: Int,
    buildNumber: String?,
    appId: String?,
    androidApplicationId: String?,
    applicationId: String?,
    versionCode: Int?,
    versionName: String?,
) {
    if (!checkSettings) return
    val expected = listOf(
        "applicationId" to androidIdOf(appId, androidApplicationId),
        "versionCode" to androidVersionCode(resolveBuildNumber(version, rebuild, buildNumber)).toString(),
        "versionName" to version.trim(),
    )
    val found = mapOf("applicationId" to applicationId, "versionCode" to versionCode?.toString(), "versionName" to versionName)
    val wrong = expected.filter { (name, value) -> found[name] != value }
    check(wrong.isEmpty()) {
        buildString {
            appendLine("settings: android: in module.yaml does not match plugins: kiteconfig. Write these lines:")
            appendLine("settings:")
            appendLine("  android:")
            wrong.forEach { (name, value) -> appendLine("    $name: $value") }
            append("Found: ").append(wrong.joinToString { (name, _) -> "$name: ${found[name]}" }).append('.')
        }
    }
}
