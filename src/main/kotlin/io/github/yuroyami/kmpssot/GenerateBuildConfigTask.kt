package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generate the runtime build-config object ([generateBuildConfigSource]) into a
 * plugin-owned generated `commonMain` dir wired onto the shared module's source
 * set — never the user's hand-authored tree. Cacheable; wipes its output before
 * regenerating so a changed package or class name can't leave a stale duplicate.
 */
@CacheableTask
abstract class GenerateBuildConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Generate the runtime build-config object into commonMain."
    }

    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val className: Property<String>
    @get:Input abstract val includeIdentity: Property<Boolean>
    @get:Input abstract val appName: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val androidApplicationId: Property<String>
    @get:Input abstract val iosBundleId: Property<String>
    @get:Input abstract val locales: ListProperty<String>
    @get:Input abstract val customFields: ListProperty<String>
    @get:Input abstract val dryRun: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val root = outputDir.get().asFile
        if (!dryRun.get()) root.deleteRecursively()
        val file = root.resolve(pkg.replace('.', '/')).resolve("${className.get()}.kt")
        writeTextSafely(
            file = file,
            content = generateBuildConfigSource(
                packageName = pkg,
                className = className.get(),
                includeIdentity = includeIdentity.get(),
                appName = appName.get(),
                versionName = versionName.get(),
                versionCode = versionCode.get(),
                androidApplicationId = androidApplicationId.get(),
                iosBundleId = iosBundleId.get(),
                locales = locales.get(),
                customFields = customFields.get(),
            ),
            backup = false,
            dryRun = dryRun.get(),
            logger = logger,
            label = "KmpSsot build config",
        )
    }
}
