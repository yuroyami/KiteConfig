package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        if (target != target.rootProject) {
            throw GradleException(
                "io.github.yuroyami.kmpssot must be applied to the root project. " +
                        "Apply it in the root build.gradle.kts, not in a submodule."
            )
        }
        if (GradleVersion.current() < GradleVersion.version(MIN_GRADLE)) {
            target.logger.warn(
                "[kmpSsot] Gradle ${GradleVersion.current().version} is older than the supported " +
                        "minimum ($MIN_GRADLE). The plugin may not behave correctly."
            )
        }

        val ext = target.extensions.create<KmpSsotExtension>("kmpSsot").apply {
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
            iosPodfilePath.convention("iosApp/Podfile")
            iosInfoPlistPath.convention("iosApp/iosApp/Info.plist")
            iosAppDir.convention("iosApp")
            iosAppiconsetPath.convention("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
            androidAppModule.convention("androidApp")
            propagateAppName.convention(true)
            propagateBundleId.convention(true)
            propagateVersion.convention(true)
            propagateLocaleList.convention(true)
            propagateLogo.convention(true)
            propagateSharedModule.convention(true)
            propagateAndroidSdk.convention(true)
            propagateInteropOptIns.convention(true)
            extraOptIns.convention(emptyList())
            syncIos.convention(true)
            sanitizeIosProject.convention(true)
            cleanupLegacyLogoArtifacts.convention(false)
            dryRun.convention(false)
            backupBeforeRewrite.convention(true)
            appLogoAndroidSafeZoneRatio.convention(66.0 / 108.0)

            // Auto-detect locales from {sharedModule}/src/commonMain/composeResources/values-*.
            locales.convention(target.provider { autoDetectLocales(target, this) })
        }

        // Nested DSL blocks. Gradle can't decorate an abstract property of a
        // non-managed type on KmpSsotExtension, so we create them explicitly and
        // expose them via getters on the parent.
        val extAware = ext as ExtensionAware
        extAware.extensions.create<KmpSsotIosExtension>("ios")
        extAware.extensions.create<KmpSsotAndroidExtension>("android")
        extAware.extensions.create<KmpSsotWebExtension>("web").apply {
            generateIoWorker.convention(false)
            ioWorkerPackage.convention("kmpssot.generated")
        }
        extAware.extensions.create<KmpSsotBuildInfoExtension>("buildInfo").apply {
            enabled.convention(false)
            packageName.convention("kmpssot.generated")
        }

        val sanitizeIosTask = registerSanitizeIosTask(target, ext)
        val syncIosTask = registerSyncIosTask(target, ext)
        val syncIosLogoTask = registerSyncIosLogoTask(target, ext)
        val syncAndroidLogoTask = registerSyncAndroidLogoTask(target, ext)
        val cleanupLegacyLogoTask = registerCleanupLegacyLogoTask(target, ext)
        registerVerifyTask(target, ext)

        // syncIosConfig relies on the Info.plist having SSOT-pointing keys, so sanitize first.
        syncIosTask.configure { dependsOn(sanitizeIosTask) }

        target.afterEvaluate {
            if (!ext.sharedModule.isPresent) {
                throw GradleException(
                    "kmpSsot { sharedModule = \"...\" } is required. Set it to the directory " +
                            "name of your KMP shared module (e.g. \"shared\" or \"composeApp\")."
                )
            }
            // Fail fast on a versionName we can't turn into a versionCode (unless overridden).
            if (ext.propagateVersion.get() && ext.versionName.isPresent && !ext.versionCodeOverride.isPresent) {
                deriveVersionCode(ext.versionName.get()) // throws GradleException with guidance if invalid
            }
            // Safe-zone ratio sanity.
            if (ext.appLogoAndroidSafeZoneRatio.isPresent) {
                val r = ext.appLogoAndroidSafeZoneRatio.get()
                if (r <= 0.0 || r > 2.0) {
                    throw GradleException(
                        "kmpSsot { appLogoAndroidSafeZoneRatio } must be in (0, 2] — got $r. " +
                                "Typical values are 0.55–0.61."
                    )
                }
            }
            // Logo: FG must be paired with exactly one BG source (PNG or colour).
            // Only validate when logo propagation is on — otherwise a stray FG with
            // propagateLogo = false shouldn't fail the build.
            if (ext.propagateLogo.get()) {
                val fgSet = ext.appLogoPngForeground.isPresent
                val bgSet = ext.appLogoPngBackground.isPresent
                val bgColorSet = ext.appLogoBackgroundColor.isPresent
                if (bgSet && bgColorSet) {
                    throw GradleException(
                        "kmpSsot { appLogoPngBackground } and { appLogoBackgroundColor } are mutually " +
                                "exclusive — set exactly one."
                    )
                }
                if (fgSet && !bgSet && !bgColorSet) {
                    throw GradleException(
                        "kmpSsot { appLogoPngForeground } is set but no background — set either " +
                                "appLogoPngBackground or appLogoBackgroundColor."
                    )
                }
                if (!fgSet && (bgSet || bgColorSet)) {
                    throw GradleException(
                        "kmpSsot background is set without a foreground — set appLogoPngForeground, " +
                                "or remove the background."
                    )
                }
                if (bgColorSet) validateLogoBackgroundColorHex(ext.appLogoBackgroundColor.get())
            }

            // Auto-cleanup of legacy logo artefacts is opt-in. When enabled, run
            // it before the regular Android sync so the new tree lands clean.
            if (ext.cleanupLegacyLogoArtifacts.get()) {
                syncAndroidLogoTask.configure { dependsOn(cleanupLegacyLogoTask) }
            }
        }

        val appModules = mutableListOf<String>()
        target.subprojects {
            val sub = this
            plugins.withId("com.android.application") {
                appModules += sub.path
                if (appModules.size == 2) {
                    sub.logger.warn(
                        "[kmpSsot] More than one com.android.application module is present — each " +
                                "receives the same applicationId/version from kmpSsot { }. Per-module " +
                                "identity overlays are not yet supported; set propagateBundleId = false " +
                                "and manage divergent ids per module (e.g. phone vs Wear/TV)."
                    )
                }
                ClassicAndroidWiring.wireApplication(sub, ext)
                hookAndroidLogoTask(sub, syncAndroidLogoTask, ext)
            }
            plugins.withId("com.android.library") { ClassicAndroidWiring.wireLibrary(sub, ext) }
            // AGP's KMP-native Android library plugin (com.android.kotlin.multiplatform.library)
            // exposes a different extension type than the classic com.android.library, so it needs
            // its own wiring. Common for the shared module in modern KMP setups (composeApp/shared).
            plugins.withId("com.android.kotlin.multiplatform.library") { KmpAndroidLibraryWiring.apply(sub, ext) }
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                hookIosFrameworkTasks(sub, syncIosTask, syncIosLogoTask, ext)
                // KGP is compileOnly: when the consumer declares kotlin("multiplatform")
                // only in a subproject's plugins block, KGP lands in a sibling
                // classloader kmp-ssot can't see, and merely CALLING a method whose
                // body references KGP types throws NoClassDefFoundError. Guard here
                // (outside any KGP-typed method) and degrade with guidance.
                if (KGP_ON_CLASSPATH) {
                    propagateInteropOptIns(sub, ext)
                    // withId fires during the subproject's `plugins {}` block, BEFORE its
                    // `kotlin { js() … }` body runs — the targets container is still empty
                    // there. wireWebIoWorker snapshots targets (unlike the lazy matching{}
                    // hooks above), so defer it to afterEvaluate.
                    sub.afterEvaluate {
                        wireWebIoWorker(sub, ext)
                        wireBuildInfo(sub, ext)
                    }
                } else {
                    sub.logger.warn(
                        "[kmpSsot] Kotlin Multiplatform plugin is applied to ${sub.path} but its classes " +
                                "are not visible to kmp-ssot's classloader — interop opt-in propagation and " +
                                "web.generateIoWorker are skipped. Declare kotlin(\"multiplatform\") " +
                                "(apply false) in the ROOT project's plugins block so both plugins share " +
                                "a classloader."
                    )
                }
            }
        }
    }

    // --- Native interop opt-in propagation ----------------------------------

    /**
     * Add the interop opt-in markers to every Kotlin/Native compilation, so
     * cinterop / Obj-C call sites don't each need an `@OptIn`. Scoped to native
     * targets, where the markers resolve — harmless and absent elsewhere.
     */
    private fun propagateInteropOptIns(project: Project, ext: KmpSsotExtension) {
        if (!ext.propagateInteropOptIns.get()) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val markers = interopOptIns(ext.extraOptIns.getOrElse(emptyList()))
        if (markers.isEmpty()) return
        kmp.targets.matching { it.platformType == KotlinPlatformType.native }.configureEach {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions.optIn.addAll(markers)
                }
            }
        }
    }

    // --- Web IO worker generation -------------------------------------------

    /**
     * Generate the inline Web Worker offload helper into the module's `jsMain`
     * source set when `kmpSsot { web { generateIoWorker = true } }`. JS target
     * only — a wasmJs-only module is logged and skipped.
     */
    private fun wireWebIoWorker(project: Project, ext: KmpSsotExtension) {
        if (!ext.web.generateIoWorker.get()) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val jsTargets = kmp.targets.filter { it.platformType == KotlinPlatformType.js }
        if (jsTargets.isEmpty()) {
            if (kmp.targets.any { it.platformType == KotlinPlatformType.wasm }) {
                project.logger.warn(
                    "[kmpSsot] web.generateIoWorker currently supports the js() target only; " +
                            "wasmJs worker generation is not yet implemented — skipping ${project.path}."
                )
            }
            return
        }

        // Validate the destination package up front — a malformed value would
        // otherwise surface as a confusing compile error inside the generated file.
        val pkg = ext.web.ioWorkerPackage.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kmpSsot { web { ioWorkerPackage } } \"$pkg\" $it")
        }

        // Derive the source set + compile task from each js target's ACTUAL name,
        // so a custom-named target (`js("web")` → webMain / compileKotlinWeb) is
        // wired too, instead of silently no-op'ing on a hardcoded `js()` name.
        jsTargets.forEach { target ->
            val name = target.targetName
            val capital = name.replaceFirstChar { it.uppercase() }
            val genDir = project.layout.buildDirectory.dir("generated/kmpssot/${name}Main/kotlin")
            val genTask = project.tasks.register<GenerateIoWorkerTask>("generateKmpSsotIoWorker$capital") {
                workerPackage.set(ext.web.ioWorkerPackage)
                outputDir.set(genDir)
                dryRun.set(ext.dryRun)
            }
            // srcDir(taskProvider.flatMap { output }) carries the task dependency to
            // EVERY consumer of the source set — compile, sourcesJar, dokka, IDE
            // import — not just a name-matched compile task.
            kmp.sourceSets.matching { it.name == "${name}Main" }.configureEach {
                kotlin.srcDir(genTask.flatMap { it.outputDir })
            }
        }
    }

    // --- Runtime build-info generation --------------------------------------

    /**
     * Generate the runtime [generateBuildInfoSource] object into the shared
     * module's `commonMain`. Scoped to the shared module only (by name), KGP-guarded
     * by the caller, deferred to `afterEvaluate` so the source sets exist.
     */
    private fun wireBuildInfo(project: Project, ext: KmpSsotExtension) {
        if (!ext.buildInfo.enabled.get()) return
        if (!ext.sharedModule.isPresent || project.name != ext.sharedModule.get()) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return

        val pkg = ext.buildInfo.packageName.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kmpSsot { buildInfo { packageName } } \"$pkg\" $it")
        }

        val genDir = project.layout.buildDirectory.dir("generated/kmpssot/commonMain/kotlin")
        val genTask = project.tasks.register<GenerateBuildInfoTask>("generateKmpSsotBuildInfo") {
            packageName.set(ext.buildInfo.packageName)
            appName.set(ext.appName.orElse(""))
            versionName.set(ext.versionName.orElse(""))
            versionCode.set(ext.versionCode.orElse(0))
            androidApplicationId.set(ext.androidApplicationId.orElse(""))
            iosBundleId.set(ext.iosBundleId.orElse(""))
            locales.set(ext.locales)
            outputDir.set(genDir)
            dryRun.set(ext.dryRun)
        }
        kmp.sourceSets.matching { it.name == "commonMain" }.configureEach {
            kotlin.srcDir(genTask.flatMap { it.outputDir })
        }
    }

    // --- Locale auto-detection ----------------------------------------------

    private fun autoDetectLocales(root: Project, ext: KmpSsotExtension): List<String> {
        if (!ext.sharedModule.isPresent) return emptyList()
        val sharedDir = root.file(ext.sharedModule.get())
        val composeRes = sharedDir.resolve("src/commonMain/composeResources")
        if (!composeRes.isDirectory) return emptyList()
        return composeRes
            .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.map { it.name.removePrefix("values-") }
            ?.filter { looksLikeLocaleQualifier(it) }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }

    // --- Task registration --------------------------------------------------

    private fun registerSanitizeIosTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SanitizeIosProjectTask> =
        root.tasks.register<SanitizeIosProjectTask>("sanitizeIosProject") {
            onlyIf { ext.syncIos.get() && ext.sanitizeIosProject.get() }
            infoPlistFile.set(root.layout.projectDirectory.file(ext.iosInfoPlistPath))
            propagateAppName.set(ext.propagateAppName)
            propagateVersion.set(ext.propagateVersion)
            usesNonExemptEncryption.set(ext.ios.usesNonExemptEncryption)
            proMotion120Hz.set(ext.ios.proMotion120Hz)
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncIosTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("syncIosConfig") {
            onlyIf { ext.syncIos.get() }
            pbxprojFile.set(root.layout.projectDirectory.file(ext.iosProjectPath))
            podfile.set(root.layout.projectDirectory.file(ext.iosPodfilePath))
            iosAppDir.set(root.layout.projectDirectory.dir(ext.iosAppDir))
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            appName.set(ext.appName)
            if (ext.bundleIdBase.isPresent) bundleId.set(ext.iosBundleId)
            locales.set(ext.locales)
            sharedModule.set(ext.sharedModule)
            oldSharedModuleName.set(ext.oldSharedModuleName)
            propagateVersion.set(ext.propagateVersion)
            propagateAppName.set(ext.propagateAppName)
            propagateBundleId.set(ext.propagateBundleId)
            propagateLocaleList.set(ext.propagateLocaleList)
            propagateSharedModule.set(ext.propagateSharedModule)
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncIosLogoTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("syncIosLogo") {
            onlyIf {
                ext.syncIos.get() && ext.propagateLogo.get() &&
                        ext.appLogoPngForeground.isPresent &&
                        (ext.appLogoPngBackground.isPresent || ext.appLogoBackgroundColor.isPresent)
            }
            foregroundPng.set(ext.appLogoPngForeground)
            backgroundPng.set(ext.appLogoPngBackground)
            backgroundColorHex.set(ext.appLogoBackgroundColor)
            val iconDir = root.layout.projectDirectory.dir(ext.iosAppiconsetPath)
            appiconsetDir.set(iconDir)
            outputFiles.from(iconDir.map { dir -> SyncIosLogoTask.OUTPUT_FILE_NAMES.map { dir.file(it) } })
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncAndroidLogoTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncAndroidLogoTask> =
        root.tasks.register<SyncAndroidLogoTask>("syncAndroidLogo") {
            onlyIf {
                ext.propagateLogo.get() &&
                        ext.appLogoPngForeground.isPresent &&
                        (ext.appLogoPngBackground.isPresent || ext.appLogoBackgroundColor.isPresent)
            }
            foregroundPng.set(ext.appLogoPngForeground)
            backgroundPng.set(ext.appLogoPngBackground)
            backgroundColorHex.set(ext.appLogoBackgroundColor)
            safeZoneRatio.set(ext.appLogoAndroidSafeZoneRatio)
            dryRun.set(ext.dryRun)
            // Resolve lazily — androidAppModule may not be set yet at register time.
            val resDir = root.layout.projectDirectory.dir(ext.androidAppModule.map { "$it/src/main/res" })
            androidResDir.set(resDir)
            outputFiles.from(resDir.map { dir -> SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.map { dir.file(it) } })
        }

    private fun registerCleanupLegacyLogoTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<CleanupLegacyAppLogoArtifactsTask> =
        root.tasks.register<CleanupLegacyAppLogoArtifactsTask>("cleanupLegacyAppLogoArtifacts") {
            dryRun.set(ext.dryRun)
            androidResDir.set(root.layout.projectDirectory.dir(
                ext.androidAppModule.map { "$it/src/main/res" }
            ))
        }

    private fun registerVerifyTask(root: Project, ext: KmpSsotExtension): TaskProvider<KmpSsotVerifyTask> =
        root.tasks.register<KmpSsotVerifyTask>("kmpSsotVerify") {
            appName.set(ext.appName)
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            if (ext.bundleIdBase.isPresent) {
                androidApplicationId.set(ext.androidApplicationId)
                iosBundleId.set(ext.iosBundleId)
            }
            locales.set(ext.locales)
            sharedModule.set(ext.sharedModule)
            pbxprojFile.set(root.layout.projectDirectory.file(ext.iosProjectPath))
            infoPlistFile.set(root.layout.projectDirectory.file(ext.iosInfoPlistPath))
            podfile.set(root.layout.projectDirectory.file(ext.iosPodfilePath))
            androidAppModule.set(ext.androidAppModule)
            compileSdk.set(ext.android.compileSdk)
            minSdk.set(ext.android.minSdk)
            targetSdk.set(ext.android.targetSdk)
            ndkVersion.set(ext.android.ndkVersion)
            javaVersion.set(ext.javaVersion)
            propagateInteropOptIns.set(ext.propagateInteropOptIns)
            generateIoWorker.set(ext.web.generateIoWorker)
            logoForeground.set(ext.appLogoPngForeground.map { true }.orElse(false))
            logoBackground.set(ext.appLogoPngBackground.map { true }.orElse(false))
            logoBackgroundColor.set(ext.appLogoBackgroundColor)
        }

    // --- Hooking new tasks --------------------------------------------------

    private fun hookIosFrameworkTasks(
        project: Project,
        syncIosTask: TaskProvider<SyncIosConfigTask>,
        syncIosLogoTask: TaskProvider<SyncIosLogoTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.syncIos.get()) return
        project.tasks.matching { isIosFrameworkLinkTaskName(it.name) }.configureEach {
            dependsOn(syncIosTask)
            dependsOn(syncIosLogoTask)
        }
    }

    private fun hookAndroidLogoTask(
        project: Project,
        syncAndroidLogoTask: TaskProvider<SyncAndroidLogoTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.propagateLogo.get()) return
        // preBuild runs before resource processing — we want logo files in place by then.
        project.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(syncAndroidLogoTask)
        }
    }

    companion object {
        private const val MIN_GRADLE = "8.5"

        /**
         * Whether the (compileOnly) Kotlin Gradle plugin classes are loadable from
         * kmp-ssot's own classloader. False when the consumer declares
         * kotlin("multiplatform") only in a subproject, which puts KGP in a sibling
         * classloader — calling into KGP-typed methods would then throw
         * NoClassDefFoundError, so those features are guarded on this.
         */
        internal val KGP_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension",
                false,
                KmpSsotPlugin::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
