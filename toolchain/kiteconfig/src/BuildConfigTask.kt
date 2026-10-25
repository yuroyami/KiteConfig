package io.github.yuroyami.kiteconfig.toolchain

import io.github.yuroyami.kiteconfig.core.canonicalizeLocales
import io.github.yuroyami.kiteconfig.core.generateBuildConfigSource
import io.github.yuroyami.kiteconfig.core.isValidKotlinIdentifier
import io.github.yuroyami.kiteconfig.core.kotlinStringLiteral
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** The task name that the generated header points at. */
private const val TASK_NAME = "buildConfig@kiteconfig"

/** Writes `KiteBuildConfig` (or the configured name) into [outputDir]. */
@OptIn(ExperimentalPathApi::class)
@TaskAction
fun generateBuildConfig(
    appName: String,
    version: String,
    rebuild: Int,
    buildNumber: String?,
    appId: String?,
    androidApplicationId: String?,
    iosBundleId: String?,
    locales: List<String>,
    buildConfig: BuildConfigSettings,
    @Output outputDir: Path,
) {
    outputDir.deleteRecursively()
    if (!buildConfig.enabled) return
    val source = generateBuildConfigSource(
        packageName = buildConfig.packageName,
        className = buildConfig.className,
        includeIdentity = true,
        appName = appName,
        versionName = version,
        versionCode = androidVersionCode(resolveBuildNumber(version, rebuild, buildNumber)),
        androidApplicationId = androidIdOf(appId, androidApplicationId),
        iosBundleId = iosIdOf(appId, iosBundleId),
        locales = canonicalizeLocales(locales),
        customFields = fieldLines(buildConfig.fields),
        regeneratedBy = TASK_NAME,
    )
    val directory = outputDir.resolve(buildConfig.packageName.replace('.', '/')).createDirectories()
    directory.resolve("${buildConfig.className}.kt").writeText(source)
}

/** The extra String fields in the line form that the shared generator reads. */
internal fun fieldLines(fields: Map<String, String>): List<String> = fields.map { (name, value) ->
    require(isValidKotlinIdentifier(name)) {
        "plugins: kiteconfig: buildConfig: fields has the name \"$name\", which is not a Kotlin identifier."
    }
    "$name: String = ${kotlinStringLiteral(value)}"
}
