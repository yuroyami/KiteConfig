package io.github.yuroyami.kiteconfig.toolchain

import io.github.yuroyami.kiteconfig.core.KiteLog
import io.github.yuroyami.kiteconfig.core.PbxprojResult
import io.github.yuroyami.kiteconfig.core.applyTextRewritePlan
import io.github.yuroyami.kiteconfig.core.canonicalizeLocales
import io.github.yuroyami.kiteconfig.core.planTextChange
import io.github.yuroyami.kiteconfig.core.readUtf8SnapshotStrict
import io.github.yuroyami.kiteconfig.core.requireContainedPath
import io.github.yuroyami.kiteconfig.core.rewritePbxproj
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.TaskAction
import java.io.File

/** The toolchain cleans `build/`, so the rewrite lock lives there. */
private const val LOCK_DIRECTORY = "build/kiteconfig"

private object ConsoleLog : KiteLog {
    override fun lifecycle(message: String) = println(message)
    override fun info(message: String) = println(message)
}

/**
 * Writes the version, the build number, the bundle id and the locales into the Xcode project.
 *
 * The project root is a String, not a Path: every module with the plugin gets this task,
 * and the toolchain allows only one task to declare a given output path.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun syncXcodeProject(
    version: String,
    rebuild: Int,
    buildNumber: String?,
    appId: String?,
    iosBundleId: String?,
    locales: List<String>,
    xcodeProject: String?,
    targets: List<String>,
    projectRootDir: String,
) {
    val root = File(projectRootDir)
    val pbxproj = pbxprojFile(root, xcodeProject) ?: return
    val snapshot = readUtf8SnapshotStrict(pbxproj)
    val result = rewrite(snapshot.text, version, rebuild, buildNumber, appId, iosBundleId, locales, targets)
    val plan = planTextChange(root, pbxproj, result.text, "project.pbxproj", snapshot)
    if (plan == null) {
        println("[kiteconfig] ${pbxproj.path} is already in line.")
        return
    }
    applyTextRewritePlan(root, listOf(plan), backup = true, dryRun = false, logger = ConsoleLog, lockDirectoryPath = LOCK_DIRECTORY)
    println("[kiteconfig] Updated ${result.changedSettings.sorted().joinToString()} in ${pbxproj.path}.")
}

/** Fails when [syncXcodeProject] would change the Xcode project. It never writes. */
@TaskAction(ExecutionAvoidance.Disabled)
fun checkXcodeProject(
    version: String,
    rebuild: Int,
    buildNumber: String?,
    appId: String?,
    iosBundleId: String?,
    locales: List<String>,
    xcodeProject: String?,
    targets: List<String>,
    projectRootDir: String,
) {
    val root = File(projectRootDir)
    val pbxproj = pbxprojFile(root, xcodeProject) ?: return
    val original = readUtf8SnapshotStrict(pbxproj).text
    val result = rewrite(original, version, rebuild, buildNumber, appId, iosBundleId, locales, targets)
    check(result.text == original) {
        "${pbxproj.path} does not match plugins: kiteconfig. A sync would change " +
            "${result.changedSettings.sorted().joinToString()}. Run ./kotlin do kiteSyncIos and commit the file."
    }
}

/** The `project.pbxproj` of [xcodeProject], or null when no Xcode project is configured. */
private fun pbxprojFile(root: File, xcodeProject: String?): File? {
    if (xcodeProject == null) return null
    val file = root.resolve(xcodeProject).resolve("project.pbxproj")
    return requireContainedPath(root, file)
}

private fun rewrite(
    original: String,
    version: String,
    rebuild: Int,
    buildNumber: String?,
    appId: String?,
    iosBundleId: String?,
    locales: List<String>,
    targets: List<String>,
): PbxprojResult {
    val result = rewritePbxproj(
        original = original,
        marketingVersion = version,
        buildNumber = resolveBuildNumber(version, rebuild, buildNumber),
        appName = null,
        bundleId = iosIdOf(appId, iosBundleId),
        locales = canonicalizeLocales(locales).ifEmpty { null },
        targetNames = targets.toSet(),
    )
    check(result.errors.isEmpty()) {
        "The Xcode project cannot be updated:\n" + result.errors.joinToString("\n") { "  $it" }
    }
    result.warnings.forEach { println("[kiteconfig] $it") }
    return result
}
