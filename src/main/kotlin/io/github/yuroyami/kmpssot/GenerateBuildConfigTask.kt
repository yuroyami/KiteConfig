package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

/**
 * Generate the runtime build-config object ([generateBuildConfigSource]) into a
 * plugin-owned generated `commonMain` dir wired onto the shared module's source
 * set — never the user's hand-authored tree. The task is cache-capable, but cache
 * storage is denied unless the DSL explicitly enables it. A checksum ownership
 * manifest removes only outputs from the previous successful generation. Unknown,
 * modified, escaping, or symlinked content fails closed instead of being deleted.
 */
@CacheableTask
abstract class GenerateBuildConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Generate the runtime build-config object into commonMain."
        generatedRoot.convention(project.layout.buildDirectory.dir("generated/kmpssot"))
        outputs.cacheIf("BuildConfig cache storage is explicitly allowed") {
            allowBuildCache.getOrElse(false)
        }
    }

    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val className: Property<String>
    @get:Input abstract val includeIdentity: Property<Boolean>
    @get:Input abstract val allowBuildCache: Property<Boolean>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val androidApplicationId: Property<String>
    @get:Internal abstract val iosBundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>
    /** Effective identity fingerprint; empty when [includeIdentity] is false. */
    @get:Input abstract val identityInputs: ListProperty<String>
    @get:Input abstract val customFields: ListProperty<String>
    /** Build-owned code generation always runs; global preview mode only applies to source rewrites. */
    @get:Internal abstract val dryRun: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val generatedRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val root = outputDir.get().asFile
        val name = className.get()
        val identityEnabled = includeIdentity.get()
        val source = generateBuildConfigSource(
            packageName = pkg,
            className = name,
            includeIdentity = identityEnabled,
            // These providers can be absent, expensive, or deliberately failing
            // in fields-only builds. Gradle does not snapshot them (@Internal),
            // and the task action must preserve that same conditional contract.
            appName = if (identityEnabled) appName.get() else "",
            versionName = if (identityEnabled) versionName.get() else "",
            versionCode = if (identityEnabled) versionCode.get() else 0,
            androidApplicationId = if (identityEnabled) androidApplicationId.get() else "",
            iosBundleId = if (identityEnabled) iosBundleId.get() else "",
            locales = if (identityEnabled) locales.get() else emptyList(),
            customFields = customFields.get(),
        )
        if (dryRun.getOrElse(false)) {
            logger.info("[kmpSsot] dryRun does not suppress build-owned BuildConfig generation.")
        }
        val relative = "${pkg.replace('.', '/')}/$name.kt"
        OwnedOutputSafety.replaceGeneratedTree(
            outputRoot = root,
            allowedRoot = generatedRoot.get().asFile,
            owner = "build-config",
            files = mapOf(relative to source.toByteArray(StandardCharsets.UTF_8)),
        )
        logger.info("[kmpSsot] Generated KmpSsot build config: ${root.resolve(relative)}")
    }
}
