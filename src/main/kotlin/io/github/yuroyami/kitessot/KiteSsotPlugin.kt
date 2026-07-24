@file:Suppress("DEPRECATION")

package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.HasConfigurableValue
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Root aggregation plugin for the `kiteSsot` application model.
 *
 * Applying the plugin configures only provider-backed Android/KMP adapters and
 * registers read-only diagnostics plus explicitly invoked migration/install tasks.
 * It does not mutate Xcode, plist, Swift, Podfile, or launcher-icon source files
 * during ordinary compilation. Destructive capabilities default off, require
 * unambiguous project/target selectors, validate containment, and fail closed.
 *
 * The supported Gradle floor is [MIN_GRADLE], and the implementation bytecode is
 * Java 17. KGP-typed integrations require KGP to be declared in the root plugin
 * classloader; requested features fail with guidance when that contract is not met.
 */
class KiteSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        if (target != target.rootProject) {
            throw GradleException(
                "io.github.yuroyami.kitessot must be applied to the root project. " +
                        "Apply it in the root build.gradle.kts, not in a submodule."
            )
        }
        if (GradleVersion.current() < GradleVersion.version(MIN_GRADLE)) {
            throw GradleException(
                "kiteSsot requires Gradle $MIN_GRADLE or newer; current Gradle is " +
                    "${GradleVersion.current().version}. Upgrade the wrapper before applying the plugin."
            )
        }
        // Resolve compatibility before the first typed peer-plugin call. An
        // unsupported runtime remains observational when no related capability is
        // requested, but its adapter classes are never touched speculatively.
        val activeKgpVersion = if (KGP_ON_CLASSPATH) runtimeKgpVersion() else null
        val activeAgpVersion = if (AGP_ON_CLASSPATH) runtimeAgpVersion() else null
        val kgpAdaptersUsable = activeKgpVersion?.let(::isSupportedKgpVersion) == true
        val agpAdaptersUsable = activeAgpVersion?.let(::isSupportedAgpVersion) == true
        val useAgp8ClassicAdapter = activeAgpVersion
            ?.let(::parseToolVersion)
            ?.major == 8

        val ext = target.extensions.create<KiteSsotExtension>("kiteSsot").apply {
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
            iosPodfilePath.convention("iosApp/Podfile")
            iosInfoPlistPath.convention("iosApp/iosApp/Info.plist")
            iosAppDir.convention("iosApp")
            iosAppiconsetPath.convention("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
            iosPbxprojFile.convention(target.layout.projectDirectory.file(iosProjectPath))
            iosPodfileFile.convention(target.layout.projectDirectory.file(iosPodfilePath))
            iosInfoPlistFile.convention(target.layout.projectDirectory.file(iosInfoPlistPath))
            iosAppDirectory.convention(target.layout.projectDirectory.dir(iosAppDir))
            iosAppIconDirectory.convention(target.layout.projectDirectory.dir(iosAppiconsetPath))
            androidAppModule.convention("androidApp")
            androidApplicationProjects.convention(emptyList())
            propagateAppName.convention(true)
            propagateBundleId.convention(true)
            propagateVersion.convention(true)
            propagateLocaleList.convention(true)
            filterAndroidResources.convention(false)
            propagateLogo.convention(false)
            propagateSharedModule.convention(false)
            propagateAndroidSdk.convention(true)
            propagateInteropOptIns.convention(false)
            extraOptIns.convention(emptyList())
            interopProjectPaths.convention(emptyList())
            syncIos.convention(false)
            sanitizeIosProject.convention(false)
            cleanupLegacyLogoArtifacts.convention(false)
            dryRun.convention(false)
            backupBeforeRewrite.convention(true)
            appLogoAndroidSafeZoneRatio.convention(66.0 / 108.0)
            iosMarketingVersion.convention(versionName)

            // Auto-detect locales from the selected Compose resources directory's values-* children.
            locales.convention(target.provider { autoDetectLocales(target, this) })
        }

        // Nested DSL blocks. Gradle can't decorate an abstract property of a
        // non-managed type on KiteSsotExtension, so we create them explicitly and
        // expose them via getters on the parent.
        val extAware = ext as ExtensionAware
        extAware.extensions.create<KiteSsotIosExtension>("ios").apply {
            targetNames.convention(emptyList())
            plistConflictPolicy.convention(PlistConflictPolicy.FAIL)
        }
        extAware.extensions.create<KiteSsotAndroidExtension>("android")
        extAware.extensions.create<KiteSsotWebExtension>("web").apply {
            generateIoWorker.convention(false)
            ioWorkerPackage.convention("kitessot.generated")
        }
        extAware.extensions.create<KiteSsotBuildConfigExtension>("buildConfig").apply {
            enabled.convention(false)
            packageName.convention("kitessot.generated")
            className.convention("BuildConfig")
            includeIdentity.convention(true)
            allowBuildCache.convention(false)
            fields.convention(emptyList())
        }

        // Keep the public DSL input immutable after root evaluation while still
        // allowing the plugin to resolve a uniquely detected application later.
        // Tasks consume this internal sink, never a mutable public property.
        val resolvedAndroidAppDirectory = target.objects.directoryProperty()

        registerSanitizeIosTask(target, ext)
        registerSyncIosTask(target, ext)
        registerSyncIosLogoTask(target, ext)
        registerSyncAndroidLogoTask(target, ext, resolvedAndroidAppDirectory)
        registerCleanupLegacyLogoTask(target, ext, resolvedAndroidAppDirectory)
        val verifyTask = registerVerifyTask(target, ext)
        val doctorTask = registerDoctorTask(target, ext, resolvedAndroidAppDirectory)
        val checkTask = registerCheckTask(target, ext, resolvedAndroidAppDirectory)
        val planTask = registerPlanTask(target)

        target.afterEvaluate {
            // Freeze the authoritative root model before any subproject build
            // script can mutate it. Diagnostic-only invocations lock without
            // realizing values so their tasks can report provider failures.
            if (isResilientDiagnosticInvocation(target)) {
                disallowModelChanges(ext)
                return@afterEvaluate
            }
            // Validate the resolved model once, before any explicit mutation task runs.
            val buildConfigIdentity = ext.buildConfig.enabled.get() && ext.buildConfig.includeIdentity.get()
            val usesAppName = ext.propagateAppName.get() || buildConfigIdentity
            val usesBundleId = ext.propagateBundleId.get() || buildConfigIdentity
            val usesVersion = ext.propagateVersion.get() || buildConfigIdentity
            val usesLocaleModel = ext.propagateLocaleList.get() || ext.filterAndroidResources.get() || buildConfigIdentity
            val usesSharedSelection = ext.buildConfig.enabled.get() || ext.propagateInteropOptIns.get() ||
                ext.web.generateIoWorker.get() || usesLocaleModel
            val usesAndroidApplicationSelection =
                (ext.propagateAppName.get() && ext.appName.isPresent) ||
                    (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) ||
                    (ext.propagateVersion.get() &&
                        (ext.versionName.isPresent || ext.versionCodeOverride.isPresent)) ||
                    ext.filterAndroidResources.get() || ext.propagateLogo.get() ||
                    ext.cleanupLegacyLogoArtifacts.get()
            if (usesAppName) ext.appName.orNull?.let(::validateAppName)
            if (usesVersion) ext.versionName.orNull?.let(::validateVersionName)
            if ((ext.propagateVersion.get() || buildConfigIdentity) &&
                ext.versionName.isPresent && !ext.versionCodeOverride.isPresent
            ) {
                deriveVersionCode(ext.versionName.get())
            }
            if (usesVersion) ext.versionCodeOverride.orNull?.let(::validateVersionCode)
            if (usesBundleId && ext.bundleIdBase.isPresent) {
                validateAndroidApplicationId(ext.androidApplicationId.get())
                validateAppleBundleId(ext.iosBundleId.get())
            }
            if (ext.syncIos.get() && ext.propagateVersion.get()) {
                ext.iosMarketingVersion.orNull?.let(::validateAppleMarketingVersion)
                ext.iosBuildNumber.orNull?.let(::validateAppleBuildNumber)
            }
            if (usesSharedSelection) {
                ext.resolvedSharedProjectPath.orNull?.let {
                    validateGradleProjectPath(it, "sharedProjectPath/sharedModule")
                }
            }
            if (usesAndroidApplicationSelection) {
                val selectedApplications = ext.androidApplicationProjects.get()
                selectedApplications.forEach { validateGradleProjectPath(it, "androidApplicationProjects") }
                if (selectedApplications.distinct().size != selectedApplications.size) {
                    throw GradleException("kiteSsot { androidApplicationProjects } contains duplicate project paths.")
                }
            }
            if (usesAndroidApplicationSelection) {
                validateRelativeProjectPath(ext.androidAppModule.get(), "androidAppModule")
            }
            if (ext.syncIos.get()) {
                listOf(
                    "iosProjectPath" to ext.iosProjectPath.get(),
                    "iosPodfilePath" to ext.iosPodfilePath.get(),
                    "iosInfoPlistPath" to ext.iosInfoPlistPath.get(),
                    "iosAppDir" to ext.iosAppDir.get(),
                    "iosAppiconsetPath" to ext.iosAppiconsetPath.get(),
                ).forEach { (name, path) -> validateRelativeProjectPath(path, name) }
                val iosTargetNames = ext.ios.targetNames.get()
                if (iosTargetNames.distinct().size != iosTargetNames.size ||
                    iosTargetNames.any { it.isBlank() || it.any(Char::isISOControl) }
                ) {
                    throw GradleException("kiteSsot { ios { targetNames } } must contain unique, non-blank names without controls.")
                }
                if (iosTargetNames.size > 1 && ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) {
                    throw GradleException(
                        "kiteSsot refuses to assign one Apple bundle identifier to multiple application targets " +
                            "(${iosTargetNames.joinToString()}). Select one target or disable propagateBundleId."
                    )
                }
            }
            ext.javaVersion.orNull?.let(::validateJavaVersion)
            if (ext.propagateInteropOptIns.get()) {
                val interopProjects = ext.interopProjectPaths.get()
                ext.extraOptIns.get().forEach(::validateOptInMarker)
                interopProjects.forEach { validateGradleProjectPath(it, "interopProjectPaths") }
                if (interopProjects.distinct().size != interopProjects.size) {
                    throw GradleException("kiteSsot { interopProjectPaths } contains duplicate project paths.")
                }
            }
            val canonicalLocales = if (usesLocaleModel) ext.canonicalLocales.get() else emptyList()
            if (ext.filterAndroidResources.get() && canonicalLocales.isEmpty()) {
                throw GradleException(
                    "kiteSsot filterAndroidResources=true requires at least one locale. " +
                        "Configure locales explicitly or add a supported values-<locale> resource directory."
                )
            }
            if (ext.buildConfig.enabled.get() && !ext.resolvedSharedProjectPath.isPresent) {
                throw GradleException(
                    "kiteSsot buildConfig is enabled but no shared project is selected. Set " +
                        "sharedProjectPath = \":shared\" (preferred) or legacy sharedModule = \"shared\"."
                )
            }
            if (ext.buildConfig.enabled.get() && ext.buildConfig.includeIdentity.get()) {
                val missing = buildList {
                    if (!ext.appName.isPresent) add("appName")
                    if (!ext.versionName.isPresent) add("versionName")
                    if (!ext.versionCode.isPresent) add("versionName or versionCodeOverride")
                    if (!ext.bundleIdBase.isPresent) add("bundleIdBase")
                }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "kiteSsot buildConfig.includeIdentity requires a complete identity; missing " +
                            missing.joinToString() + ". Set includeIdentity=false for fields-only generation."
                    )
                }
            }
            if (ext.syncIos.get() && ext.propagateSharedModule.get()) {
                if (!ext.resolvedIosSharedModuleName.isPresent || !ext.resolvedIosPreviousSharedModuleName.isPresent) {
                    throw GradleException(
                        "kiteSsot shared-module migration requires both iosSharedModuleName (new) and " +
                            "iosPreviousSharedModuleName (old). Automatic Pod/Swift rename inference is disabled."
                    )
                }
                runCatching {
                    requireSwiftModuleIdentifier(ext.resolvedIosSharedModuleName.get(), "iosSharedModuleName")
                    requireSwiftModuleIdentifier(ext.resolvedIosPreviousSharedModuleName.get(), "iosPreviousSharedModuleName")
                }.getOrElse { failure ->
                    throw GradleException(failure.message ?: "Invalid iOS shared-module migration identifier", failure)
                }
            }
            // Safe-zone ratio sanity.
            if (ext.propagateLogo.get() && ext.appLogoAndroidSafeZoneRatio.isPresent) {
                validateLogoSafeZoneRatio(ext.appLogoAndroidSafeZoneRatio.get())
            }
            // Logo: FG must be paired with exactly one BG source (PNG or colour).
            // Only validate when logo propagation is on — otherwise a stray FG with
            // propagateLogo = false shouldn't fail the build.
            if (ext.propagateLogo.get()) {
                val fgSet = ext.appLogoPngForeground.isPresent
                val bgSet = ext.appLogoPngBackground.isPresent
                val bgColorSet = ext.appLogoBackgroundColor.isPresent
                if (!fgSet && !bgSet && !bgColorSet) {
                    throw GradleException(
                        "kiteSsot propagateLogo=true requires appLogoPngForeground plus exactly one of " +
                            "appLogoPngBackground or appLogoBackgroundColor."
                    )
                }
                if (bgSet && bgColorSet) {
                    throw GradleException(
                        "kiteSsot { appLogoPngBackground } and { appLogoBackgroundColor } are mutually " +
                                "exclusive — set exactly one."
                    )
                }
                if (fgSet && !bgSet && !bgColorSet) {
                    throw GradleException(
                        "kiteSsot { appLogoPngForeground } is set but no background — set either " +
                                "appLogoPngBackground or appLogoBackgroundColor."
                    )
                }
                if (!fgSet && (bgSet || bgColorSet)) {
                    throw GradleException(
                        "kiteSsot background is set without a foreground — set appLogoPngForeground, " +
                                "or remove the background."
                    )
                }
                if (bgColorSet) validateLogoBackgroundColorHex(ext.appLogoBackgroundColor.get())
                if (ext.syncIos.get()) {
                    val deploymentTarget = ext.ios.deploymentTarget.orNull ?: throw GradleException(
                        "kiteSsot universal iOS AppIcon propagation requires ios.deploymentTarget >= 12.0 " +
                            "and Xcode 14 or newer."
                    )
                    validateUniversalAppIconDeploymentTarget(deploymentTarget)
                }
            }

            // Legacy takeover is validated as part of the Android installer's
            // transaction. The standalone cleanup task remains available for an
            // explicitly requested one-shot recovery workflow.
            if (ext.cleanupLegacyLogoArtifacts.get()) {
                if (!ext.propagateLogo.get() || !ext.appLogoPngForeground.isPresent ||
                    (!ext.appLogoPngBackground.isPresent && !ext.appLogoBackgroundColor.isPresent)
                ) {
                    throw GradleException(
                        "cleanupLegacyLogoArtifacts requires an enabled, complete replacement logo plan. " +
                            "Set propagateLogo=true plus foreground and exactly one background."
                    )
                }
            }
            finalizeModel(ext)
        }

        val detectedAndroidApplications = linkedSetOf<String>()
        val detectedAndroidProjects = linkedSetOf<String>()
        val detectedKmpProjects = linkedSetOf<String>()
        val detectedKotlinJvmProjects = linkedSetOf<String>()
        val kmpAndroidProjectsWithoutComponents = linkedSetOf<String>()
        val androidProjectsWithoutSharedClassloader = linkedSetOf<String>()
        val kmpProjectsWithoutSharedClassloader = linkedSetOf<String>()
        target.allprojects {
            val consumerProject = this
            plugins.withId("com.android.application") {
                detectedAndroidApplications += consumerProject.path
                detectedAndroidProjects += consumerProject.path
                if (!isResilientDiagnosticInvocation(target)) {
                    if (agpAdaptersUsable) {
                        if (useAgp8ClassicAdapter) {
                            Agp8ClassicAndroidWiringBridge.wireApplication(consumerProject, ext)
                        } else {
                            ClassicAndroidWiring.wireApplication(consumerProject, ext)
                        }
                    } else if (!AGP_ON_CLASSPATH) {
                        androidProjectsWithoutSharedClassloader += consumerProject.path
                    }
                }
            }
            plugins.withId("com.android.library") {
                detectedAndroidProjects += consumerProject.path
                if (!isResilientDiagnosticInvocation(target)) {
                    if (agpAdaptersUsable) {
                        if (useAgp8ClassicAdapter) {
                            Agp8ClassicAndroidWiringBridge.wireLibrary(consumerProject, ext)
                        } else {
                            ClassicAndroidWiring.wireLibrary(consumerProject, ext)
                        }
                    } else if (!AGP_ON_CLASSPATH) {
                        androidProjectsWithoutSharedClassloader += consumerProject.path
                    }
                }
            }
            // AGP's KMP-native Android library plugin (com.android.kotlin.multiplatform.library)
            // exposes a different extension type than the classic com.android.library, so it needs
            // its own wiring. Common for the shared module in modern KMP setups (composeApp/shared).
            plugins.withId("com.android.kotlin.multiplatform.library") {
                detectedAndroidProjects += consumerProject.path
                if (!isResilientDiagnosticInvocation(target)) {
                    if (agpAdaptersUsable) {
                        if (!KmpAndroidLibraryWiring.apply(consumerProject, ext)) {
                            kmpAndroidProjectsWithoutComponents += consumerProject.path
                        }
                    } else if (!AGP_ON_CLASSPATH) {
                        androidProjectsWithoutSharedClassloader += consumerProject.path
                    }
                }
            }
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                detectedKmpProjects += consumerProject.path
                // KGP is compileOnly: when the consumer declares kotlin("multiplatform")
                // only in a subproject's plugins block, KGP lands in a sibling
                // classloader kitessot can't see, and merely CALLING a method whose
                // body references KGP types throws NoClassDefFoundError. Guard here
                // (outside any KGP-typed method) and degrade with guidance.
                if (isResilientDiagnosticInvocation(target)) {
                    // Diagnostic/report tasks resolve provider failures themselves;
                    // do not let peer-plugin adapters pre-empt those reports.
                } else if (kgpAdaptersUsable) {
                    propagateInteropOptIns(consumerProject, ext)
                    // withId fires during the subproject's `plugins {}` block, BEFORE its
                    // `kotlin { js() … }` body runs — the targets container is still empty
                    // there. wireWebIoWorker snapshots targets (unlike the lazy matching{}
                    // hooks above), so defer it to afterEvaluate.
                    consumerProject.afterEvaluate {
                        ext.javaVersion.orNull?.let { javaVersion ->
                            val targetVersion = org.gradle.api.JavaVersion
                                .toVersion(validateJavaVersion(javaVersion))
                                .toString()
                            KotlinJvmTargetWiring.apply(consumerProject, targetVersion)
                        }
                        wireWebIoWorker(consumerProject, ext)
                        wireBuildConfig(consumerProject, ext)
                    }
                } else if (!KGP_ON_CLASSPATH) {
                    kmpProjectsWithoutSharedClassloader += consumerProject.path
                }
            }
            listOf("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.jvm").forEach { pluginId ->
                plugins.withId(pluginId) {
                    detectedKotlinJvmProjects += consumerProject.path
                    if (isResilientDiagnosticInvocation(target)) {
                        // See the KMP branch above.
                    } else if (kgpAdaptersUsable) {
                        consumerProject.afterEvaluate {
                            ext.javaVersion.orNull?.let { javaVersion ->
                                val targetVersion = org.gradle.api.JavaVersion
                                    .toVersion(validateJavaVersion(javaVersion))
                                    .toString()
                                KotlinJvmTargetWiring.apply(consumerProject, targetVersion)
                            }
                        }
                    } else if (!KGP_ON_CLASSPATH) {
                        kmpProjectsWithoutSharedClassloader += consumerProject.path
                    }
                }
            }
        }

        target.gradle.projectsEvaluated {
            val diagnosticSelection = runCatching { ext.androidApplicationProjects.get() }.getOrDefault(emptyList())
            val diagnosticApplications = diagnosticSelection.ifEmpty { detectedAndroidApplications.toList() }
            val detectedDirectories = diagnosticApplications.mapNotNull { path ->
                target.findProject(path)?.layout?.projectDirectory?.asFile
            }
            val explicitAndroidDirectory = runCatching { ext.androidAppDirectory.asFile.orNull }.getOrNull()
            val legacyDirectory = runCatching {
                target.layout.projectDirectory.dir(ext.androidAppModule.get()).asFile
            }.getOrNull()
            val manifestDirectories = detectedDirectories.ifEmpty {
                listOfNotNull(explicitAndroidDirectory ?: legacyDirectory)
            }
            val resourceDirectories = listOfNotNull(explicitAndroidDirectory).ifEmpty {
                detectedDirectories.ifEmpty { listOfNotNull(legacyDirectory) }
            }
            verifyTask.configure {
                androidApplicationProjects.set(diagnosticApplications)
                androidAppDirectories.set(manifestDirectories.map { it.path })
            }
            fun diagnosticBoolean(value: () -> Boolean): Boolean = runCatching(value).getOrDefault(false)
            val diagnosticNeedsAndroidIntegration = detectedAndroidProjects.isNotEmpty() && (
                diagnosticBoolean { ext.propagateAppName.get() && ext.appName.isPresent } ||
                    diagnosticBoolean { ext.propagateBundleId.get() && ext.bundleIdBase.isPresent } ||
                    diagnosticBoolean {
                        ext.propagateVersion.get() &&
                            (ext.versionName.isPresent || ext.versionCodeOverride.isPresent)
                    } ||
                    diagnosticBoolean { ext.propagateAndroidSdk.get() && listOf(
                        ext.android.compileSdk,
                        ext.android.minSdk,
                        ext.android.targetSdk,
                        ext.android.ndkVersion,
                    ).any { it.isPresent } } ||
                    diagnosticBoolean { ext.javaVersion.isPresent } ||
                    diagnosticBoolean { ext.filterAndroidResources.get() }
                )
            val diagnosticNeedsKgpIntegration =
                diagnosticBoolean { ext.propagateInteropOptIns.get() } ||
                    diagnosticBoolean { ext.web.generateIoWorker.get() } ||
                    diagnosticBoolean { ext.buildConfig.enabled.get() } ||
                    (diagnosticBoolean { ext.javaVersion.isPresent } &&
                        (detectedKmpProjects.isNotEmpty() || detectedKotlinJvmProjects.isNotEmpty()))
            listOf(doctorTask, checkTask).forEach { diagnosticTask ->
                diagnosticTask.configure {
                    detectedAndroidApplicationProjects.set(detectedAndroidApplications.toList())
                    androidManifestPaths.set(
                        manifestDirectories.map { it.resolve("src/main/AndroidManifest.xml").path },
                    )
                    androidResPaths.set(resourceDirectories.map { it.resolve("src/main/res").path })
                    agpRequired.set(diagnosticNeedsAndroidIntegration)
                    kgpRequired.set(diagnosticNeedsKgpIntegration)
                }
            }
            configurePlanTask(
                root = target,
                ext = ext,
                planTask = planTask,
                detectedApplications = detectedAndroidApplications.toList(),
                androidResourceDirectories = resourceDirectories.map { it.resolve("src/main/res") },
            )
            if (isResilientDiagnosticInvocation(target)) {
                return@projectsEvaluated
            }
            if (detectedAndroidProjects.isNotEmpty() && ext.propagateAndroidSdk.get()) {
                validateSdkLevels(
                    ext.android.compileSdk.orNull,
                    ext.android.minSdk.orNull,
                    ext.android.targetSdk.orNull,
                )
                ext.android.ndkVersion.orNull?.let(::validateNdkVersion)
            }
            if (detectedAndroidApplications.isNotEmpty() && ext.propagateVersion.get()) {
                ext.android.publishedVersionCode.orNull?.let { published ->
                    validatePublishedVersionCode(ext.versionCode.orNull, published)
                }
            }
            val needsApplicationSelection =
                (ext.propagateAppName.get() && ext.appName.isPresent) ||
                    (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) ||
                    (ext.propagateVersion.get() &&
                        (ext.versionName.isPresent || ext.versionCodeOverride.isPresent)) ||
                    ext.filterAndroidResources.get()
            val usesApplicationSelection = needsApplicationSelection || ext.propagateLogo.get() ||
                ext.cleanupLegacyLogoArtifacts.get()
            val selectedApplications = if (usesApplicationSelection) {
                ext.androidApplicationProjects.get()
            } else {
                emptyList()
            }
            when {
                selectedApplications.isEmpty() && detectedAndroidApplications.size > 1 && needsApplicationSelection ->
                    throw GradleException(
                        "kiteSsot found multiple Android application projects while app-scoped values are enabled: " +
                            "${detectedAndroidApplications.joinToString()}. Select the intended targets " +
                            "explicitly with androidApplicationProjects.add(\":app\")."
                    )

                selectedApplications.isNotEmpty() -> {
                    val unknown = selectedApplications.toSet() - detectedAndroidApplications
                    if (unknown.isNotEmpty()) {
                        throw GradleException(
                            "kiteSsot { androidApplicationProjects } contains project paths that do not " +
                                "apply com.android.application: ${unknown.sorted().joinToString()}."
                        )
                    }
                    if (selectedApplications.size > 1 && ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) {
                        throw GradleException(
                            "kiteSsot refuses to assign one Android applicationId to multiple selected apps " +
                                "(${selectedApplications.joinToString()}). Select one app or disable " +
                                "propagateBundleId and configure unique ids in each app module."
                        )
                    }
                }
            }

            val effectiveApplications = selectedApplications.ifEmpty { detectedAndroidApplications.toList() }
            if ((ext.propagateLogo.get() || ext.cleanupLegacyLogoArtifacts.get()) &&
                effectiveApplications.size > 1
            ) {
                throw GradleException(
                    "kiteSsot Android logo installation has one output sink but resolved multiple " +
                        "Android application projects: ${effectiveApplications.joinToString()}. " +
                        "Select exactly one with androidApplicationProjects.add(\":app\")."
                )
            }

            val resolvedApplicationPath = selectedApplications.singleOrNull()
                ?: detectedAndroidApplications.singleOrNull()
            val detectedOrLegacyDirectory = resolvedApplicationPath
                ?.let(target::findProject)
                ?.layout
                ?.projectDirectory
                ?: if (usesApplicationSelection) {
                    target.layout.projectDirectory.dir(ext.androidAppModule.get())
                } else {
                    target.layout.projectDirectory
                }
            resolvedAndroidAppDirectory.set(ext.androidAppDirectory.orElse(detectedOrLegacyDirectory))
            resolvedAndroidAppDirectory.finalizeValue()

            val needsKgpIntegration = ext.propagateInteropOptIns.get() ||
                ext.web.generateIoWorker.get() || ext.buildConfig.enabled.get() ||
                (ext.javaVersion.isPresent &&
                    (detectedKmpProjects.isNotEmpty() || detectedKotlinJvmProjects.isNotEmpty()))
            doctorTask.configure { kgpRequired.set(needsKgpIntegration) }
            checkTask.configure { kgpRequired.set(needsKgpIntegration) }
            if (needsKgpIntegration && kmpProjectsWithoutSharedClassloader.isNotEmpty()) {
                throw GradleException(
                    "kiteSsot cannot configure requested KMP integrations in " +
                        "${kmpProjectsWithoutSharedClassloader.joinToString()}: Kotlin Gradle plugin " +
                        "classes are isolated in a sibling classloader. Declare " +
                        "kotlin(\"multiplatform\") (apply false) in the root plugins block so KGP and " +
                        "kitessot share a classloader. No requested integration was silently skipped."
                    )
            }

            val needsAndroidIntegration = detectedAndroidProjects.isNotEmpty() && (
                (ext.propagateAppName.get() && ext.appName.isPresent) ||
                    (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) ||
                    (ext.propagateVersion.get() &&
                        (ext.versionName.isPresent || ext.versionCodeOverride.isPresent)) ||
                    (ext.propagateAndroidSdk.get() && listOf(
                        ext.android.compileSdk,
                        ext.android.minSdk,
                        ext.android.targetSdk,
                        ext.android.ndkVersion,
                    ).any { it.isPresent }) ||
                    ext.javaVersion.isPresent || ext.filterAndroidResources.get()
                )
            if (needsAndroidIntegration && androidProjectsWithoutSharedClassloader.isNotEmpty()) {
                throw GradleException(
                    "kiteSsot cannot configure requested Android integrations in " +
                        "${androidProjectsWithoutSharedClassloader.joinToString()}: AGP classes are " +
                        "isolated in a sibling classloader. Declare the Android plugin versions with " +
                        "apply false in the root plugins block. No requested Android value was silently skipped."
                )
            }
            if (needsAndroidIntegration && AGP_ON_CLASSPATH) {
                val agpVersion = activeAgpVersion
                    ?: throw GradleException(
                        "[KITESSOT-COMPAT-001] Could not determine the active Android Gradle plugin version. " +
                            "Supported AGP range is 8.5.2 through 9.2.x."
                    )
                if (!isSupportedAgpVersion(agpVersion)) {
                    throw GradleException(
                        "[KITESSOT-COMPAT-002] Unsupported Android Gradle plugin $agpVersion; " +
                            "this kitessot build supports AGP 8.5.2 through 9.2.x."
                    )
                }
            }
            val needsKmpAndroidSdk = ext.propagateAndroidSdk.get() &&
                (ext.android.compileSdk.isPresent || ext.android.minSdk.isPresent)
            if (needsKmpAndroidSdk && kmpAndroidProjectsWithoutComponents.isNotEmpty()) {
                throw GradleException(
                    "[KITESSOT-COMPAT-005] The KMP Android components extension was unavailable in " +
                        kmpAndroidProjectsWithoutComponents.joinToString() +
                        "; compileSdk/minSdk were not silently skipped. Verify the supported AGP/KGP plugin shape."
                )
            }
            if (needsKgpIntegration && KGP_ON_CLASSPATH) {
                val kgpVersion = activeKgpVersion
                    ?: throw GradleException(
                        "[KITESSOT-COMPAT-003] Could not determine the active Kotlin Gradle plugin version. " +
                            "This kitessot build supports KGP 2.4.x."
                    )
                if (!isSupportedKgpVersion(kgpVersion)) {
                    throw GradleException(
                        "[KITESSOT-COMPAT-004] Unsupported Kotlin Gradle plugin $kgpVersion; " +
                            "this kitessot build supports KGP 2.4.x."
                    )
                }
            }

            val selectedSharedProject = ext.resolvedSharedProjectPath.orNull
            if (ext.buildConfig.enabled.get() && selectedSharedProject !in detectedKmpProjects) {
                throw GradleException(
                    "kiteSsot buildConfig project '$selectedSharedProject' does not apply " +
                        "org.jetbrains.kotlin.multiplatform."
                )
            }
            if (ext.propagateInteropOptIns.get()) {
                val interopProjects = ext.interopProjectPaths.get().ifEmpty {
                    listOfNotNull(selectedSharedProject)
                }
                if (interopProjects.isEmpty()) {
                    throw GradleException(
                        "kiteSsot interop opt-ins need an explicit KMP scope. Set " +
                            "interopProjectPaths.add(\":shared\") or sharedProjectPath."
                    )
                }
                val invalid = interopProjects.toSet() - detectedKmpProjects
                if (invalid.isNotEmpty()) {
                    throw GradleException(
                        "kiteSsot interop project selector(s) do not apply Kotlin Multiplatform: " +
                            invalid.sorted().joinToString()
                    )
                }
            }
            if (ext.web.generateIoWorker.get()) {
                val webProjects = ext.web.projectPaths.get()
                webProjects.forEach { validateGradleProjectPath(it, "web.projectPaths") }
                if (webProjects.distinct().size != webProjects.size) {
                    throw GradleException("kiteSsot { web { projectPaths } } contains duplicate project paths.")
                }
                val effectiveWebProjects = webProjects.ifEmpty {
                    listOfNotNull(selectedSharedProject)
                }
                if (effectiveWebProjects.isEmpty()) {
                    throw GradleException(
                        "kiteSsot web worker generation needs an explicit KMP project. Set " +
                            "web.projectPaths.add(\":shared\") or sharedProjectPath = \":shared\"."
                    )
                }
                val invalid = effectiveWebProjects.toSet() - detectedKmpProjects
                if (invalid.isNotEmpty()) {
                    throw GradleException(
                        "kiteSsot web project selector(s) do not apply Kotlin Multiplatform: " +
                            invalid.sorted().joinToString()
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
    private fun propagateInteropOptIns(project: Project, ext: KiteSsotExtension) {
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val markers = project.provider {
            val selectedProjects = ext.interopProjectPaths.get().ifEmpty {
                listOfNotNull(ext.resolvedSharedProjectPath.orNull)
            }
            if (ext.propagateInteropOptIns.get() && project.path in selectedProjects) {
                interopOptIns(ext.extraOptIns.get())
            } else {
                emptyList()
            }
        }
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
     * Generate the inline Web Worker offload helper into each explicitly selected
     * Kotlin/JS target source set when
     * `kiteSsot { web { generateIoWorker = true } }`. wasmJs and non-JS selectors
     * are rejected.
     */
    private fun wireWebIoWorker(project: Project, ext: KiteSsotExtension) {
        if (!ext.web.generateIoWorker.get()) return
        val selectedProjects = ext.web.projectPaths.get().ifEmpty {
            listOfNotNull(ext.resolvedSharedProjectPath.orNull)
        }
        if (project.path !in selectedProjects) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val requestedNames = ext.web.browserTargetNames.get()
        if (requestedNames.isEmpty()) {
            throw GradleException(
                "kiteSsot web worker generation is enabled for ${project.path}, but no browser target " +
                    "was selected. Add web { browserTargetNames.add(\"js\") } (or the exact custom " +
                    "Kotlin/JS browser target name). Browser capability is never inferred for Node targets."
            )
        }
        if (requestedNames.any { it.isBlank() } || requestedNames.distinct().size != requestedNames.size) {
            throw GradleException(
                "kiteSsot { web { browserTargetNames } } must contain unique, non-blank target names."
            )
        }
        val allTargetsByName = kmp.targets.associateBy { it.targetName }
        val invalidTargets = requestedNames.filter { allTargetsByName[it]?.platformType != KotlinPlatformType.js }
        if (invalidTargets.isNotEmpty()) {
            throw GradleException(
                "kiteSsot browser target selector(s) ${invalidTargets.joinToString()} in ${project.path} " +
                    "do not resolve to Kotlin/JS targets. wasmJs and Node-only targets are unsupported."
            )
        }
        val jsTargets = requestedNames.map { allTargetsByName.getValue(it) }

        // Validate the destination package up front — a malformed value would
        // otherwise surface as a confusing compile error inside the generated file.
        val pkg = ext.web.ioWorkerPackage.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kiteSsot { web { ioWorkerPackage } } \"$pkg\" $it")
        }

        // Derive the source set + compile task from each js target's ACTUAL name,
        // so a custom-named target (`js("web")` → webMain / compileKotlinWeb) is
        // wired too, instead of silently no-op'ing on a hardcoded `js()` name.
        jsTargets.forEach { target ->
            val name = target.targetName
            val capital = name.replaceFirstChar { it.uppercase() }
            val genDir = project.layout.buildDirectory.dir("generated/kitessot/${name}Main/kotlin")
            val genTask = project.tasks.register<GenerateIoWorkerTask>("generateKiteSsotIoWorker$capital") {
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

    // --- Runtime build-config generation ------------------------------------

    /**
     * Generate the runtime [generateBuildConfigSource] object into the shared
     * module's `commonMain`. Scoped to the resolved shared project path,
     * KGP-guarded by the caller, and deferred to `afterEvaluate` so the source
     * sets exist.
     */
    private fun wireBuildConfig(project: Project, ext: KiteSsotExtension) {
        val cfg = ext.buildConfig
        if (!cfg.enabled.get()) return
        if (!ext.resolvedSharedProjectPath.isPresent || project.path != ext.resolvedSharedProjectPath.get()) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return

        val pkg = cfg.packageName.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kiteSsot { buildConfig { packageName } } \"$pkg\" $it")
        }
        val cls = cfg.className.get()
        if (!isValidKotlinIdentifier(cls)) {
            throw GradleException(
                "kiteSsot { buildConfig { className } } \"$cls\" is not a valid Kotlin identifier."
            )
        }

        val genDir = project.layout.buildDirectory.dir("generated/kitessot/commonMain/kotlin")
        val genTask = project.tasks.register<GenerateBuildConfigTask>("generateKiteSsotBuildConfig") {
            packageName.set(cfg.packageName)
            className.set(cfg.className)
            includeIdentity.set(cfg.includeIdentity)
            allowBuildCache.set(cfg.allowBuildCache)
            appName.set(ext.appName.orElse(""))
            versionName.set(ext.versionName.orElse(""))
            versionCode.set(ext.versionCode.orElse(0))
            androidApplicationId.set(ext.androidApplicationId.orElse(""))
            iosBundleId.set(ext.iosBundleId.orElse(""))
            locales.set(ext.canonicalLocales)
            identityInputs.set(project.provider {
                if (!cfg.includeIdentity.get()) {
                    emptyList()
                } else {
                    listOf(
                        ext.appName.orNull.orEmpty(),
                        ext.versionName.orNull.orEmpty(),
                        ext.versionCode.orNull?.toString().orEmpty(),
                        ext.androidApplicationId.orNull.orEmpty(),
                        ext.iosBundleId.orNull.orEmpty(),
                    ) + ext.canonicalLocales.get()
                }
            })
            customFields.set(cfg.fields)
            outputDir.set(genDir)
            dryRun.set(ext.dryRun)
        }
        kmp.sourceSets.matching { it.name == "commonMain" }.configureEach {
            kotlin.srcDir(genTask.flatMap { it.outputDir })
        }
    }

    // --- Locale auto-detection ----------------------------------------------

    private fun autoDetectLocales(root: Project, ext: KiteSsotExtension): List<String> {
        val composeRes = when {
            ext.composeResourcesDirectory.isPresent -> ext.composeResourcesDirectory.get().asFile
            ext.resolvedSharedProjectPath.isPresent -> {
                val sharedProject = root.findProject(ext.resolvedSharedProjectPath.get()) ?: return emptyList()
                sharedProject.projectDir.resolve("src/commonMain/composeResources")
            }
            else -> return emptyList()
        }
        return detectComposeResourceLocales(composeRes)
    }

    // --- Task registration --------------------------------------------------

    private fun registerSanitizeIosTask(
        root: Project,
        ext: KiteSsotExtension,
    ): TaskProvider<SanitizeIosProjectTask> =
        root.tasks.register<SanitizeIosProjectTask>("kiteSsotSanitizeIosProject") {
            onlyIf { ext.syncIos.get() && ext.sanitizeIosProject.get() }
            projectRootDir.set(root.layout.projectDirectory)
            infoPlistFile.set(ext.iosInfoPlistFile)
            propagateAppName.set(ext.appName.map { ext.propagateAppName.get() }.orElse(false))
            propagateMarketingVersion.set(
                ext.iosMarketingVersion.map { ext.propagateVersion.get() }.orElse(false)
            )
            propagateBuildNumber.set(
                ext.iosBuildNumber.map { ext.propagateVersion.get() }.orElse(false)
            )
            usesNonExemptEncryption.set(ext.ios.usesNonExemptEncryption)
            proMotion120Hz.set(ext.ios.proMotion120Hz)
            conflictPolicy.set(ext.ios.plistConflictPolicy)
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncIosTask(
        root: Project,
        ext: KiteSsotExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("kiteSsotSyncIosConfig") {
            onlyIf { ext.syncIos.get() }
            projectRootDir.set(root.layout.projectDirectory)
            pbxprojFile.set(ext.iosPbxprojFile)
            infoPlistFile.set(ext.iosInfoPlistFile)
            podfile.set(ext.iosPodfileFile)
            iosAppDir.set(ext.iosAppDirectory)
            appiconsetDir.set(ext.iosAppIconDirectory)
            marketingVersion.set(ext.iosMarketingVersion)
            buildNumber.set(ext.iosBuildNumber)
            appName.set(ext.appName)
            bundleId.set(ext.iosBundleId)
            locales.set(ext.canonicalLocales)
            targetNames.set(ext.ios.targetNames)
            iosSharedModuleName.set(ext.resolvedIosSharedModuleName)
            iosPreviousSharedModuleName.set(ext.resolvedIosPreviousSharedModuleName)
            propagateVersion.set(ext.propagateVersion)
            propagateAppName.set(ext.appName.map { ext.propagateAppName.get() }.orElse(false))
            propagateBundleId.set(ext.bundleIdBase.map { ext.propagateBundleId.get() }.orElse(false))
            propagateLocaleList.set(
                ext.canonicalLocales.map { it.isNotEmpty() && ext.propagateLocaleList.get() }
            )
            propagateSharedModule.set(ext.propagateSharedModule)
            propagateLogo.set(ext.propagateLogo)
            sanitizeSourcePlist.set(ext.sanitizeIosProject)
            usesNonExemptEncryption.set(ext.ios.usesNonExemptEncryption)
            proMotion120Hz.set(ext.ios.proMotion120Hz)
            plistConflictPolicy.set(ext.ios.plistConflictPolicy)
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncIosLogoTask(
        root: Project,
        ext: KiteSsotExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("kiteSsotSyncIosLogo") {
            onlyIf { ext.syncIos.get() && ext.propagateLogo.get() }
            foregroundPng.set(ext.appLogoPngForeground)
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.set(ext.appLogoPngBackground)
            backgroundColorHex.set(ext.appLogoBackgroundColor)
            appiconsetDir.set(ext.iosAppIconDirectory)
            outputFiles.from(ext.iosAppIconDirectory.map { dir -> SyncIosLogoTask.OUTPUT_FILE_NAMES.map { dir.file(it) } })
            dryRun.set(ext.dryRun)
            backup.set(ext.backupBeforeRewrite)
        }

    private fun registerSyncAndroidLogoTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
    ): TaskProvider<SyncAndroidLogoTask> =
        root.tasks.register<SyncAndroidLogoTask>("kiteSsotSyncAndroidLogo") {
            onlyIf { ext.propagateLogo.get() }
            foregroundPng.set(ext.appLogoPngForeground)
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.set(ext.appLogoPngBackground)
            backgroundColorHex.set(ext.appLogoBackgroundColor)
            safeZoneRatio.set(ext.appLogoAndroidSafeZoneRatio)
            emitMonochrome.set(ext.android.compileSdk.map { it >= 33 }.orElse(false))
            cleanupLegacyArtifacts.set(ext.cleanupLegacyLogoArtifacts)
            dryRun.set(ext.dryRun)
            // Resolve lazily — androidAppModule may not be set yet at register time.
            val resDir = resolvedAndroidAppDirectory.dir("src/main/res")
            androidResDir.set(resDir)
            outputFiles.from(resDir.map { dir -> SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.map { dir.file(it) } })
        }

    private fun registerCleanupLegacyLogoTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
    ): TaskProvider<CleanupLegacyAppLogoArtifactsTask> =
        root.tasks.register<CleanupLegacyAppLogoArtifactsTask>("kiteSsotCleanupLegacyAppLogoArtifacts") {
            onlyIf { ext.cleanupLegacyLogoArtifacts.get() }
            projectRootDir.set(root.layout.projectDirectory)
            dryRun.set(ext.dryRun)
            androidResDir.set(resolvedAndroidAppDirectory.dir("src/main/res"))
        }

    private fun registerVerifyTask(root: Project, ext: KiteSsotExtension): TaskProvider<KiteSsotVerifyTask> =
        root.tasks.register<KiteSsotVerifyTask>("kiteSsotVerify") {
            appName.set(ext.appName)
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            androidApplicationId.set(ext.androidApplicationId)
            iosBundleId.set(ext.iosBundleId)
            locales.set(ext.canonicalLocales)
            iosSharedModuleName.set(ext.resolvedIosSharedModuleName)
            pbxprojFile.set(ext.iosPbxprojFile)
            infoPlistFile.set(ext.iosInfoPlistFile)
            podfile.set(ext.iosPodfileFile)
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

    private fun registerDoctorTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
    ): TaskProvider<KiteSsotDoctorTask> =
        root.tasks.register<KiteSsotDoctorTask>("kiteSsotDoctor") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory)
        }

    private fun registerCheckTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
    ): TaskProvider<KiteSsotCheckTask> =
        root.tasks.register<KiteSsotCheckTask>("kiteSsotCheck") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory)
        }

    private fun registerPlanTask(root: Project): TaskProvider<KiteSsotPlanTask> =
        root.tasks.register<KiteSsotPlanTask>("kiteSsotPlan")

    private fun configurePlanTask(
        root: Project,
        ext: KiteSsotExtension,
        planTask: TaskProvider<KiteSsotPlanTask>,
        detectedApplications: List<String>,
        androidResourceDirectories: List<java.io.File>,
    ) {
        planTask.configure {
            operations.set(root.provider {
                buildList {
                    if (ext.syncIos.get() && ext.sanitizeIosProject.get()) {
                        add("sanitize source Info.plist in the iOS text transaction")
                    }
                    if (ext.syncIos.get()) add("migrate selected Xcode build settings")
                    if (ext.syncIos.get() && ext.propagateSharedModule.get()) {
                        add("migrate explicit Pod/Swift module references")
                    }
                    if (ext.syncIos.get() && ext.propagateLogo.get()) add("install iOS AppIcon assets")
                    if (ext.propagateLogo.get()) add("install Android launcher assets")
                    if (ext.cleanupLegacyLogoArtifacts.get()) {
                        add("transactionally take over legacy Android logo artifacts")
                    }
                }
            })
            mutationPaths.set(root.provider {
                buildList {
                    fun addTextTarget(file: java.io.File) {
                        add(file.path)
                        if (ext.backupBeforeRewrite.get()) add(file.path + BACKUP_SUFFIX)
                    }
                    if (ext.syncIos.get()) {
                        addTextTarget(ext.iosPbxprojFile.get().asFile)
                        add(root.layout.projectDirectory.file(".gradle/kitessot/rewrite.lock").asFile.path)
                    }
                    if (ext.syncIos.get() && ext.sanitizeIosProject.get()) {
                        addTextTarget(ext.iosInfoPlistFile.get().asFile)
                    }
                    if (ext.syncIos.get() && ext.propagateSharedModule.get()) {
                        addTextTarget(ext.iosPodfileFile.get().asFile)
                        add(ext.iosAppDirectory.get().asFile.path)
                    }
                    if (ext.syncIos.get() && ext.propagateLogo.get()) {
                        val icons = ext.iosAppIconDirectory.get().asFile
                        add(icons.path)
                        val identity = SyncIosLogoTask.catalogIdentity(root.projectDir, icons)
                        val metadata = (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
                            .resolve(".kitessot/$identity")
                        val manifest = metadata.resolve("owned-files-v1")
                        add(manifest.path)
                        add(manifest.path + ".lock")
                        if (ext.backupBeforeRewrite.get()) {
                            add(root.projectDir.resolve(".kitessot/recovery/ios-appicon/$identity").path)
                        }
                    }
                    if (ext.propagateLogo.get() || ext.cleanupLegacyLogoArtifacts.get()) {
                        androidResourceDirectories.forEach { res ->
                            add(res.path)
                            val manifest = res.parentFile.resolve(".kitessot/android-logo-owned-files-v1")
                            add(manifest.path)
                            add(manifest.path + ".lock")
                        }
                    }
                    if (ext.cleanupLegacyLogoArtifacts.get()) {
                        val recovery = root.projectDir.resolve(".kitessot/recovery/android-logo")
                        add(recovery.path)
                        add(recovery.resolve("removal-provenance.tsv").path)
                        add(recovery.resolve(".migration.lock").path)
                    }
                }
            })
            selectedTargets.set(root.provider {
                ext.androidApplicationProjects.get().ifEmpty { detectedApplications }
                    .map { "Android project $it" } +
                    ext.ios.targetNames.get().map { "Xcode target $it" }
            })
            policies.set(root.provider {
                mapOf(
                    "backupBeforeRewrite" to ext.backupBeforeRewrite.get().toString(),
                    "dryRun" to ext.dryRun.get().toString(),
                    "plistConflictPolicy" to ext.ios.plistConflictPolicy.get().name,
                    "iosDeploymentTarget" to ext.ios.deploymentTarget.orNull.orEmpty(),
                    "pbxprojScope" to if (ext.ios.targetNames.get().isEmpty()) {
                        "sole application target only"
                    } else {
                        "explicit targets"
                    },
                    "sourceMutationDuringBuild" to "disabled",
                )
            })
            exactChanges.set(root.provider {
                buildList {
                    if (ext.syncIos.get()) {
                        add(
                            "iOS text transaction: calculate target-scoped unified diffs from " +
                                ext.iosPbxprojFile.get().asFile.path,
                        )
                    }
                    if (ext.syncIos.get() && ext.sanitizeIosProject.get()) {
                        add(
                            "Info.plist: include the configured SSOT references/flags under " +
                                "${ext.ios.plistConflictPolicy.get()} policy",
                        )
                    }
                    if (ext.syncIos.get() && ext.propagateLogo.get()) {
                        add(
                            "iOS AppIcon: align the selected target's existing catalog setting, then render " +
                                "AppIcon-1024.png and Contents.json after ownership validation",
                        )
                    }
                    if (ext.propagateLogo.get()) {
                        val versionQualifier = if (ext.android.compileSdk.orNull?.let { it >= 33 } == true) {
                            "/v33"
                        } else {
                            ""
                        }
                        add(
                            "Android icons: render density PNGs plus v26$versionQualifier wrappers " +
                                "after ownership validation; KMPS003 verifies the user-owned manifest references",
                        )
                    }
                }
            })
            notes.set(
                listOf(
                    "Set dryRun=true and invoke an individual text migration task for its unified-style preview; " +
                        "binary installers list exact owned paths.",
                    "Generated BuildConfig/worker source is build-owned and is not part of this source mutation plan.",
                    "The listed iOS app directory is a bounded discovery root; any matching Swift rewrite receives a sibling $BACKUP_SUFFIX backup when backups are enabled.",
                    "Any provider-backed section that cannot be resolved is warned about and omitted; no mutation runs.",
                ),
            )
        }
    }

    private fun KiteSsotDiagnosticTaskBase.bindDiagnosticInputs(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
    ) {
        propagateAppName.set(ext.propagateAppName)
        appName.set(ext.appName)
        propagateBundleId.set(ext.propagateBundleId)
        iosBundleId.set(ext.iosBundleId)
        propagateVersion.set(ext.propagateVersion)
        versionName.set(ext.versionName)
        hasVersionCodeOverride.set(ext.versionCodeOverride.map { true }.orElse(false))
        propagateLocaleList.set(ext.propagateLocaleList)
        locales.set(ext.locales)
        filterAndroidResources.set(ext.filterAndroidResources)
        syncIos.set(ext.syncIos)
        sanitizeIosProject.set(ext.sanitizeIosProject)
        propagateLogo.set(ext.propagateLogo)
        cleanupLegacyLogoArtifacts.set(ext.cleanupLegacyLogoArtifacts)
        iosMarketingVersion.set(ext.iosMarketingVersion)
        iosBuildNumber.set(ext.iosBuildNumber)
        iosDeploymentTarget.set(ext.ios.deploymentTarget)
        usesNonExemptEncryption.set(ext.ios.usesNonExemptEncryption)
        proMotion120Hz.set(ext.ios.proMotion120Hz)
        plistConflictPolicy.set(ext.ios.plistConflictPolicy)
        iosTargetNames.set(ext.ios.targetNames)
        androidApplicationProjects.set(ext.androidApplicationProjects)
        manifestFile.set(resolvedAndroidAppDirectory.file("src/main/AndroidManifest.xml"))
        infoPlistFile.set(ext.iosInfoPlistFile)
        pbxprojFile.set(ext.iosPbxprojFile)
        appiconsetDir.set(ext.iosAppIconDirectory)
        androidResDir.set(resolvedAndroidAppDirectory.dir("src/main/res"))
        projectRootDir.set(root.layout.projectDirectory)
        androidEmitMonochrome.set(ext.android.compileSdk.map { it >= 33 }.orElse(false))
        androidLogoInputFingerprint.set(root.provider {
            if (!ext.propagateLogo.get()) {
                "disabled"
            } else {
                logoInputFingerprintForFiles(
                    rendererVersion = SyncAndroidLogoTask.RENDERER_FINGERPRINT_VERSION,
                    foreground = ext.appLogoPngForeground.asFile.get(),
                    background = ext.appLogoPngBackground.asFile.orNull,
                    backgroundColor = ext.appLogoBackgroundColor.orNull,
                    parameters = mapOf(
                        "emitMonochrome" to (ext.android.compileSdk.orNull?.let { it >= 33 } == true).toString(),
                        "safeZoneRatio" to ext.appLogoAndroidSafeZoneRatio.get().toString(),
                    ),
                )
            }
        })
        iosLogoInputFingerprint.set(root.provider {
            if (!ext.syncIos.get() || !ext.propagateLogo.get()) {
                "disabled"
            } else {
                logoInputFingerprintForFiles(
                    rendererVersion = SyncIosLogoTask.RENDERER_FINGERPRINT_VERSION,
                    foreground = ext.appLogoPngForeground.asFile.get(),
                    background = ext.appLogoPngBackground.asFile.orNull,
                    backgroundColor = ext.appLogoBackgroundColor.orNull,
                )
            }
        })
        agpOnClasspath.set(AGP_ON_CLASSPATH)
        if (AGP_ON_CLASSPATH) runtimeAgpVersion()?.let(this.activeAgpVersion::set)
        kgpOnClasspath.set(KGP_ON_CLASSPATH)
        if (KGP_ON_CLASSPATH) runtimeKgpVersion()?.let(this.activeKgpVersion::set)
        kgpRequired.set(
            root.provider {
                ext.propagateInteropOptIns.get() || ext.web.generateIoWorker.get() ||
                    ext.buildConfig.enabled.get()
            }
        )
    }

    /**
     * True when every explicitly requested task is a resilient report task or a
     * lifecycle alias whose complete dependency closure contains only such tasks.
     * Resolving aliases here avoids brittle command-line name matching while still
     * keeping mixed builds (for example `check` plus diagnostics) fail-fast.
     */
    private fun isResilientDiagnosticInvocation(root: Project): Boolean {
        val requested = root.gradle.startParameter.taskNames
        if (requested.isEmpty()) return false

        fun requestedTask(path: String): Task? {
            val segments = path.removePrefix(":").split(':').filter(String::isNotBlank)
            if (segments.isEmpty()) return null
            val projectPath = if (segments.size == 1) ":" else ":" + segments.dropLast(1).joinToString(":")
            val tasks = root.findProject(projectPath)?.tasks ?: return null
            val requestedName = segments.last()
            tasks.findByName(requestedName)?.let { return it }
            val prefixMatches = tasks.names.filter { it.startsWith(requestedName) }
            return prefixMatches.singleOrNull()?.let(tasks::findByName)
        }

        fun resilient(task: Task, visiting: MutableSet<String>): Boolean {
            if (task.name in RESILIENT_DIAGNOSTIC_TASKS) return true
            if (!visiting.add(task.path)) return false
            val dependencies = runCatching { task.taskDependencies.getDependencies(task) }.getOrElse { return false }
            return dependencies.isNotEmpty() && dependencies.all { resilient(it, visiting.toMutableSet()) }
        }

        return requested.all { path ->
            path.substringAfterLast(':') in RESILIENT_DIAGNOSTIC_TASKS ||
                requestedTask(path)?.let { resilient(it, mutableSetOf()) } == true
        }
    }

    /** Freeze every validated DSL input before subprojects can observe it. */
    private fun finalizeModel(ext: KiteSsotExtension) {
        modelValues(ext).forEach { it.finalizeValue() }
    }

    /** Prevent late mutation without realizing provider-backed diagnostic values. */
    private fun disallowModelChanges(ext: KiteSsotExtension) {
        modelValues(ext).forEach { it.disallowChanges() }
    }

    private fun modelValues(ext: KiteSsotExtension): List<HasConfigurableValue> =
        listOf(
            ext.appName,
            ext.versionName,
            ext.bundleIdBase,
            ext.iosMarketingVersion,
            ext.iosBuildNumber,
            ext.iosBundleSuffix,
            ext.androidApplicationIdSuffix,
            ext.versionCodeOverride,
            ext.javaVersion,
            ext.locales,
            ext.sharedModule,
            ext.sharedProjectPath,
            ext.iosSharedModuleName,
            ext.iosPreviousSharedModuleName,
            ext.composeResourcesDirectory,
            ext.androidAppDirectory,
            ext.androidAppModule,
            ext.androidApplicationProjects,
            ext.oldSharedModuleName,
            ext.appLogoPngForeground,
            ext.appLogoPngBackground,
            ext.appLogoBackgroundColor,
            ext.appLogoAndroidSafeZoneRatio,
            ext.iosProjectPath,
            ext.iosPodfilePath,
            ext.iosInfoPlistPath,
            ext.iosAppDir,
            ext.iosAppiconsetPath,
            ext.iosPbxprojFile,
            ext.iosPodfileFile,
            ext.iosInfoPlistFile,
            ext.iosAppDirectory,
            ext.iosAppIconDirectory,
            ext.propagateAppName,
            ext.propagateBundleId,
            ext.propagateVersion,
            ext.propagateLocaleList,
            ext.filterAndroidResources,
            ext.propagateLogo,
            ext.propagateSharedModule,
            ext.propagateAndroidSdk,
            ext.propagateInteropOptIns,
            ext.extraOptIns,
            ext.interopProjectPaths,
            ext.syncIos,
            ext.sanitizeIosProject,
            ext.cleanupLegacyLogoArtifacts,
            ext.dryRun,
            ext.backupBeforeRewrite,
            ext.ios.targetNames,
            ext.ios.deploymentTarget,
            ext.ios.plistConflictPolicy,
            ext.ios.usesNonExemptEncryption,
            ext.ios.proMotion120Hz,
            ext.android.publishedVersionCode,
            ext.android.compileSdk,
            ext.android.minSdk,
            ext.android.targetSdk,
            ext.android.ndkVersion,
            ext.web.generateIoWorker,
            ext.web.browserTargetNames,
            ext.web.projectPaths,
            ext.web.ioWorkerPackage,
            ext.buildConfig.enabled,
            ext.buildConfig.packageName,
            ext.buildConfig.className,
            ext.buildConfig.includeIdentity,
            ext.buildConfig.allowBuildCache,
            ext.buildConfig.fields,
        )

    companion object {
        private const val MIN_GRADLE = "8.5"
        private val RESILIENT_DIAGNOSTIC_TASKS = setOf(
            "kiteSsotVerify",
            "kiteSsotDoctor",
            "kiteSsotCheck",
            "kiteSsotPlan",
        )

        /**
         * Whether the (compileOnly) Kotlin Gradle plugin classes are loadable from
         * kitessot's own classloader. False when the consumer declares
         * kotlin("multiplatform") only in a subproject, which puts KGP in a sibling
         * classloader — calling into KGP-typed methods would then throw
         * NoClassDefFoundError, so those features are guarded on this.
         */
        internal val KGP_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension",
                false,
                KiteSsotPlugin::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

        internal val AGP_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "com.android.build.api.variant.ApplicationAndroidComponentsExtension",
                false,
                KiteSsotPlugin::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

        private fun runtimeKgpVersion(): String? = runCatching {
            Class.forName(
                "org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension",
                false,
                KiteSsotPlugin::class.java.classLoader,
            ).`package`.implementationVersion
        }.getOrNull()

        private fun runtimeAgpVersion(): String? = runCatching {
            val versionClass = Class.forName(
                "com.android.build.api.AndroidPluginVersion",
                false,
                KiteSsotPlugin::class.java.classLoader,
            )
            val current = versionClass.getMethod("getCurrent").invoke(null)
            versionClass.getMethod("getVersion").invoke(current)?.toString()
        }.getOrNull()
    }
}
