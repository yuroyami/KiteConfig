package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Rewrites iOS configuration files in place to match the kmpSsot { } DSL.
 *
 * pbxproj keys (gated per `propagate*` toggle + value presence):
 *  - propagateVersion     → MARKETING_VERSION, CURRENT_PROJECT_VERSION
 *  - propagateAppName     → INFOPLIST_KEY_CFBundleDisplayName / CFBundleName, PRODUCT_NAME
 *  - propagateBundleId    → PRODUCT_BUNDLE_IDENTIFIER (every occurrence)
 *  - propagateLocaleList  → knownRegions
 *
 * Shared module rename (gated on `propagateSharedModule`):
 *  - Podfile `pod 'X', :path => '../X'` rewritten if X != sharedModule
 *  - Every plain `import X` in `{iosAppDir}/**/*.swift` rewritten to `import sharedModule`
 *
 * The actual rewrites live in pure functions ([rewritePbxproj],
 * [rewriteSwiftImport], …) so they can be unit tested. All writes go through
 * [writeTextSafely], honouring `dryRun` and `backupBeforeRewrite`.
 */
@DisableCachingByDefault(because = "Trivial text rewrite; caching adds overhead without payoff.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Sync iOS pbxproj + Podfile + Swift imports from the kmpSsot { } DSL."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty
    @get:Internal abstract val iosAppDir: DirectoryProperty

    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val bundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val sharedModule: Property<String>
    @get:Internal abstract val oldSharedModuleName: Property<String>

    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val propagateBundleId: Property<Boolean>
    @get:Internal abstract val propagateLocaleList: Property<Boolean>
    @get:Internal abstract val propagateSharedModule: Property<Boolean>

    @get:Internal abstract val dryRun: Property<Boolean>
    @get:Internal abstract val backup: Property<Boolean>

    @TaskAction
    fun sync() {
        syncPbxproj()
        syncSharedModuleReferences()
    }

    // --- pbxproj rewrites ----------------------------------------------------

    private fun syncPbxproj() {
        val file = pbxprojFile.asFile.get()
        if (!file.exists()) {
            logger.warn("[kmpSsot] pbxproj not found at ${file.path} — skipping pbxproj sync.")
            return
        }

        val result = rewritePbxproj(
            original = file.readText(),
            versionName = if (propagateVersion.get() && versionName.isPresent) versionName.get() else null,
            // Gate on versionCode itself (not versionName) so a lone versionCodeOverride
            // still writes CURRENT_PROJECT_VERSION.
            versionCode = if (propagateVersion.get() && versionCode.isPresent) versionCode.get() else null,
            appName = if (propagateAppName.get() && appName.isPresent) appName.get() else null,
            bundleId = if (propagateBundleId.get() && bundleId.isPresent) bundleId.get() else null,
            locales = if (propagateLocaleList.get()) locales.get() else null,
        )
        result.warnings.forEach { logger.warn("[kmpSsot] $it") }

        val wrote = writeTextSafely(file, result.text, backup.get(), dryRun.get(), logger, "iOS pbxproj")
        if (wrote) {
            logger.lifecycle(
                "[kmpSsot] iOS pbxproj synced: " +
                        "name=${appName.orNull ?: "[unchanged]"}, " +
                        "v=${versionName.orNull ?: "[unchanged]"} (${versionCode.orNull ?: "-"}), " +
                        "id=${bundleId.orNull ?: "[unchanged]"}, " +
                        "locales=${locales.orNull?.takeIf { it.isNotEmpty() } ?: "[unchanged]"}"
            )
        } else if (!dryRun.get()) {
            logger.info("[kmpSsot] iOS pbxproj already in sync.")
        }
    }

    // --- Shared module rename SSOT ------------------------------------------

    private fun syncSharedModuleReferences() {
        if (!propagateSharedModule.get()) return
        if (!sharedModule.isPresent) return

        val newName = sharedModule.get()
        val oldName = resolveOldName() ?: return

        if (oldName == newName) {
            logger.info("[kmpSsot] Shared module name already \"$newName\"; nothing to rewrite.")
            return
        }

        val podChanged = rewritePodfile(oldName, newName)
        val swiftCount = rewriteSwiftImports(oldName, newName)
        if (podChanged || swiftCount > 0) {
            logger.lifecycle(
                "[kmpSsot] Shared module references migrated: \"$oldName\" → \"$newName\"" +
                        (if (swiftCount > 0) " ($swiftCount Swift file(s))" else "") +
                        ". Run `pod install` in the iOS app dir to refresh the Pods workspace."
            )
        } else {
            logger.info("[kmpSsot] Shared module rename \"$oldName\" → \"$newName\": no references found to rewrite.")
        }
    }

    /**
     * The old shared-module name: an explicit [oldSharedModuleName] wins; otherwise
     * auto-detect from the Podfile — but refuse to guess when more than one local
     * dev-pod is present (renaming the wrong pod would rewrite an unrelated
     * dependency's Podfile line and Swift imports).
     */
    private fun resolveOldName(): String? {
        oldSharedModuleName.orNull?.let { return it }
        val podText = podfile.asFile.orNull?.takeIf { it.exists() }?.readText() ?: return null
        val candidates = detectPodSharedModuleCandidates(podText)
        return when {
            candidates.isEmpty() -> null
            candidates.size > 1 -> {
                logger.warn(
                    "[kmpSsot] Podfile has multiple local dev-pods (${candidates.joinToString(", ")}) — " +
                            "cannot safely auto-detect the old shared-module name. Set " +
                            "kmpSsot { oldSharedModuleName = \"...\" } to enable the rename, or " +
                            "propagateSharedModule = false to disable it."
                )
                null
            }
            else -> candidates.single()
        }
    }

    private fun rewritePodfile(oldName: String, newName: String): Boolean {
        val file = podfile.asFile.orNull?.takeIf { it.exists() } ?: return false
        val updated = rewritePodfileContent(file.readText(), oldName, newName)
        return writeTextSafely(file, updated, backup.get(), dryRun.get(), logger, "iOS Podfile")
    }

    private fun rewriteSwiftImports(oldName: String, newName: String): Int {
        val dir = iosAppDir.asFile.orNull?.takeIf { it.isDirectory } ?: return 0
        var rewritten = 0
        dir.walkTopDown()
            // Never descend into vendored pods, build output, or Xcode derived/user
            // data — rewriting `import` in third-party or generated Swift is wrong.
            .onEnter { it.name !in PRUNED_DIRS }
            .filter { it.isFile && it.extension == "swift" }
            .forEach { swift ->
                val updated = rewriteSwiftImport(swift.readText(), oldName, newName)
                if (writeTextSafely(swift, updated, backup.get(), dryRun.get(), logger, "Swift import in ${swift.name}")) {
                    rewritten++
                }
            }
        return rewritten
    }

    private companion object {
        /** Directory names never walked for Swift rewrites (vendored / generated / IDE state). */
        val PRUNED_DIRS = setOf("Pods", "build", ".build", "DerivedData", "xcuserdata", ".git")
    }
}
