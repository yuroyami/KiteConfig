package io.github.yuroyami.kmpssot

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion

class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        check(target == target.rootProject) {
            "io.github.yuroyami.kmpssot must be applied to the root project. " +
                    "Apply it in the root build.gradle.kts, not in a submodule."
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

            // Auto-cleanup of legacy logo artefacts is opt-in. When enabled, run
            // it before the regular Android sync so the new tree lands clean.
            if (ext.cleanupLegacyLogoArtifacts.get()) {
                syncAndroidLogoTask.configure { dependsOn(cleanupLegacyLogoTask) }
            }
        }

        target.subprojects {
            val sub = this
            plugins.withId("com.android.application") {
                wireAndroidApp(sub, ext)
                hookAndroidLogoTask(sub, syncAndroidLogoTask, ext)
            }
            plugins.withId("com.android.library") { wireAndroidLibrary(sub, ext) }
            // AGP's KMP-native Android library plugin (com.android.kotlin.multiplatform.library)
            // exposes a different extension type than the classic com.android.library, so it needs
            // its own wiring. Common for the shared module in modern KMP setups (composeApp/shared).
            plugins.withId("com.android.kotlin.multiplatform.library") { KmpAndroidLibraryWiring.apply(sub, ext) }
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                hookIosFrameworkTasks(sub, syncIosTask, syncIosLogoTask, ext)
            }
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
        }

    // --- Hooking new tasks --------------------------------------------------

    private fun hookIosFrameworkTasks(
        project: Project,
        syncIosTask: TaskProvider<SyncIosConfigTask>,
        syncIosLogoTask: TaskProvider<SyncIosLogoTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.syncIos.get()) return
        val iosTaskFilter: (org.gradle.api.Task) -> Boolean = {
            it.name.startsWith("linkPodReleaseFrameworkIos") ||
                    it.name.startsWith("linkPodDebugFrameworkIos") ||
                    it.name == "embedAndSignAppleFrameworkForXcode"
        }
        project.tasks.matching(iosTaskFilter).configureEach {
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

    // --- Android application wiring -----------------------------------------

    private fun wireAndroidApp(project: Project, ext: KmpSsotExtension) {
        val android = project.extensions.getByType(ApplicationExtension::class.java)

        android.defaultConfig.apply {
            if (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) {
                applicationId = ext.androidApplicationId.get()
            }
            if (ext.propagateVersion.get() && ext.versionName.isPresent) {
                versionCode = ext.versionCode.get()
                versionName = ext.versionName.get()
            }
            if (ext.propagateAppName.get() && ext.appName.isPresent) {
                manifestPlaceholders["appName"] = ext.appName.get()
            }
            if (ext.propagateLocaleList.get()) {
                val l = ext.locales.get()
                if (l.isNotEmpty()) resourceConfigurations.addAll(l)
            }
        }

        if (ext.propagateAndroidSdk.get()) {
            val sdk = ext.android
            if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
            if (sdk.ndkVersion.isPresent) android.ndkVersion = sdk.ndkVersion.get()
            if (sdk.minSdk.isPresent) android.defaultConfig.minSdk = sdk.minSdk.get()
            if (sdk.targetSdk.isPresent) android.defaultConfig.targetSdk = sdk.targetSdk.get()
        }

        applyJavaVersion(ext) { jv ->
            android.compileOptions.sourceCompatibility = jv
            android.compileOptions.targetCompatibility = jv
        }
    }

    // --- Android library wiring ---------------------------------------------

    private fun wireAndroidLibrary(project: Project, ext: KmpSsotExtension) {
        val android = project.extensions.getByType(LibraryExtension::class.java)

        if (ext.propagateLocaleList.get()) {
            val l = ext.locales.get()
            if (l.isNotEmpty()) android.defaultConfig.resourceConfigurations.addAll(l)
        }

        if (ext.propagateAndroidSdk.get()) {
            val sdk = ext.android
            if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
            if (sdk.ndkVersion.isPresent) android.ndkVersion = sdk.ndkVersion.get()
            if (sdk.minSdk.isPresent) android.defaultConfig.minSdk = sdk.minSdk.get()
            // Library modules have no targetSdk (removed by AGP).
        }

        applyJavaVersion(ext) { jv ->
            android.compileOptions.sourceCompatibility = jv
            android.compileOptions.targetCompatibility = jv
        }
    }

    /** Apply javaVersion only when the user set it — no silent default override. */
    private inline fun applyJavaVersion(ext: KmpSsotExtension, set: (JavaVersion) -> Unit) {
        if (!ext.javaVersion.isPresent) return
        set(JavaVersion.toVersion(ext.javaVersion.get()))
    }

    companion object {
        private const val MIN_GRADLE = "8.5"
    }
}
