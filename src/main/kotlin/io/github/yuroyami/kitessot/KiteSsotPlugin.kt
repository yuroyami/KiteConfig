@file:Suppress("DEPRECATION")

package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.provider.HasConfigurableValue
import org.gradle.api.provider.Provider
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

        val ext = target.extensions.create<KiteSsotExtension>("kiteSsot")

        // Nested DSL blocks. Gradle can't decorate an abstract property of a
        // non-managed type on KiteSsotExtension, so we create them explicitly and
        // expose them via getters on the parent.
        val extAware = ext as ExtensionAware
        extAware.extensions.create<KiteSsotModulesExtension>("modules").apply {
            androidApps.convention(emptyList())
            desktopApps.convention(emptyList())
        }
        extAware.extensions.create<KiteSsotPropagateExtension>("propagate")
        extAware.extensions.create<KiteSsotAndroidExtension>("android").apply {
            rebuild.convention(0)
        }
        val iosExtension = extAware.extensions.create<KiteSsotIosExtension>("ios").apply {
            rebuild.convention(0)
        }
        (iosExtension as ExtensionAware).extensions.create<KiteSsotIosSyncExtension>("sync").apply {
            targets.convention(emptyList())
        }
        extAware.extensions.create<KiteSsotLogoExtension>("logo")
        extAware.extensions.create<KiteSsotNativeOptInsExtension>("nativeOptIns").apply {
            builtIns.convention(true)
            markers.convention(emptyList())
            projects.convention(emptyList())
        }
        val webExtension = extAware.extensions.create<KiteSsotWebExtension>("web")
        (webExtension as ExtensionAware).extensions.create<KiteSsotIoWorkerExtension>("ioWorker").apply {
            targets.convention(emptyList())
            projects.convention(emptyList())
        }
        extAware.extensions.create<KiteSsotBuildConfigExtension>("buildConfig").apply {
            packageName.convention(KiteSsotExtension.DEFAULT_GENERATED_PACKAGE)
            className.convention("BuildConfig")
            includeIdentity.convention(true)
            allowBuildCache.convention(false)
            fields.convention(emptyList())
        }
        extAware.extensions.create<KiteSsotDesktopExtension>("desktop").apply {
            rebuild.convention(0)
        }

        // Resolve console colour once. Tasks receive only the Boolean, which keeps
        // them configuration-cache safe and stops every task action re-detecting.
        val colorSupported = target.provider {
            resolveColorSupport(
                explicit = target.providers.gradleProperty("kitessot.color").orNull
                    ?.let { strictBooleanProperty("kitessot.color", it) },
                noColorEnv = target.providers.environmentVariable("NO_COLOR").orNull,
                term = target.providers.environmentVariable("TERM").orNull,
                console = when (target.gradle.startParameter.consoleOutput) {
                    ConsoleOutput.Plain -> ConsoleMode.PLAIN
                    ConsoleOutput.Rich, ConsoleOutput.Verbose -> ConsoleMode.RICH
                    else -> ConsoleMode.AUTO
                },
                terminalAttached = System.console() != null,
            )
        }

        // Command-line mirrors: an invocation-level override always wins over
        // whatever the build script says, since their whole point is a CI run
        // overriding a checked-in build without editing it.
        // Validated eagerly, not through a lazy map: only some tasks read these,
        // so a lazy parse would accept "-Pkitessot.backups=treu" on one invocation
        // and silently drop backups on the next. A protection switch has to reject
        // a value it does not understand the moment it is supplied.
        target.providers.gradleProperty("kitessot.dryRun").orNull
            ?.let { ext.dryRunOverride.set(strictBooleanProperty("kitessot.dryRun", it)) }
        target.providers.gradleProperty("kitessot.backups").orNull
            ?.let { ext.backupsOverride.set(strictBooleanProperty("kitessot.backups", it)) }

        // Path and locale defaults are set on the pre-3.0 properties on purpose:
        // each resolution chain reads the 3.0 block first and only falls through
        // to these, so a convention here can never shadow a 3.0 value.
        ext.apply {
            iosPbxprojFile.convention(
                target.layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj"),
            )
            iosPodfileFile.convention(target.layout.projectDirectory.file("iosApp/Podfile"))
            iosInfoPlistFile.convention(target.layout.projectDirectory.file("iosApp/iosApp/Info.plist"))
            iosAppDirectory.convention(target.layout.projectDirectory.dir("iosApp"))
            iosAppIconDirectory.convention(
                target.layout.projectDirectory.dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"),
            )
            // Auto-detect locales from the selected Compose resources directory's values-* children.
            locales.convention(target.provider { autoDetectLocales(target, ext) })
        }

        // Keep the public DSL input immutable after root evaluation while still
        // allowing the plugin to resolve a uniquely detected application later.
        // Tasks consume this internal sink, never a mutable public property.
        val resolvedAndroidAppDirectory = target.objects.directoryProperty()
        // Whether the Android output sink is a real, chosen application module.
        // Defaults to false so an unresolved sink refuses to install rather than
        // quietly filling the root project with launcher resources nothing packages.
        val androidOutputApproved = target.objects.property(Boolean::class.java).convention(false)

        registerSanitizeIosTask(target, ext)
        registerSyncIosTask(target, ext)
        registerSyncIosLogoTask(target, ext)
        registerSyncAndroidLogoTask(target, ext, resolvedAndroidAppDirectory, androidOutputApproved)
        registerCleanupLegacyLogoTask(target, ext, resolvedAndroidAppDirectory, androidOutputApproved)
        val verifyTask = registerVerifyTask(target, ext, colorSupported)
        val doctorTask = registerDoctorTask(target, ext, resolvedAndroidAppDirectory, colorSupported)
        val checkTask = registerCheckTask(target, ext, resolvedAndroidAppDirectory, colorSupported)
        val planTask = registerPlanTask(target, colorSupported)

        target.afterEvaluate {
            // Freeze the authoritative root model before any subproject build
            // script can mutate it. Diagnostic-only invocations lock without
            // realizing values so their tasks can report provider failures.
            if (isResilientDiagnosticInvocation(target)) {
                disallowModelChanges(ext)
                return@afterEvaluate
            }
            // Validate the resolved model once, before any explicit mutation task runs.
            val buildConfigIdentity = ext.effectiveBuildConfigEnabled.get() && ext.buildConfig.includeIdentity.get()
            val usesAppName = ext.effectivePropagateAppName.get() || buildConfigIdentity
            val usesBundleId = ext.effectivePropagateBundleId.get() || buildConfigIdentity
            val usesVersion = ext.effectivePropagateVersion.get() || buildConfigIdentity
            val usesLocaleModel = ext.effectivePropagateLocales.get() || ext.effectiveFilterAndroidResources.get() || buildConfigIdentity
            val usesSharedSelection = ext.effectiveBuildConfigEnabled.get() || ext.effectiveNativeOptInsEnabled.get() ||
                ext.effectiveIoWorkerEnabled.get() || usesLocaleModel
            val usesAndroidApplicationSelection =
                (ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) ||
                    (ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) ||
                    (ext.effectivePropagateVersion.get() &&
                        (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)) ||
                    ext.effectiveFilterAndroidResources.get() || ext.effectivePropagateLogo.get() ||
                    ext.effectiveTakeOverLegacyIcons.get()
            if (usesAppName) ext.effectiveAppName.orNull?.let(::validateAppName)
            if (usesVersion) ext.effectiveVersion.orNull?.let(::validateVersionName)
            if ((ext.effectivePropagateVersion.get() || buildConfigIdentity) &&
                ext.effectiveVersion.isPresent && !ext.effectiveAndroidVersionCode.isPresent
            ) {
                ext.effectiveAndroidVersionCode.get()
            }
            if (usesVersion) ext.effectiveAndroidVersionCode.orNull?.let(::validateVersionCode)
            if (usesBundleId && ext.effectiveAppId.isPresent) {
                validateAndroidApplicationId(ext.androidApplicationId.get())
                validateAppleBundleId(ext.iosBundleId.get())
            }
            if (ext.effectiveSyncIos.get() && ext.effectivePropagateVersion.get()) {
                ext.effectiveIosMarketingVersion.orNull?.let(::validateAppleMarketingVersion)
                ext.effectiveIosBuildNumber.orNull?.let(::validateAppleBuildNumber)
            }
            if (usesSharedSelection) {
                ext.effectiveSharedProjectPath.orNull?.let {
                    validateGradleProjectPath(it, "modules { shared }")
                }
            }
            if (usesAndroidApplicationSelection) {
                val selectedApplications = ext.effectiveAndroidApps.get()
                selectedApplications.forEach { validateGradleProjectPath(it, "modules { androidApps }") }
                if (selectedApplications.distinct().size != selectedApplications.size) {
                    throw GradleException("kiteSsot { modules { androidApps } } contains duplicate project paths.")
                }
            }
            if (ext.effectiveSyncIos.get()) {
                val iosTargetNames = ext.effectiveIosTargets.get()
                if (iosTargetNames.distinct().size != iosTargetNames.size ||
                    iosTargetNames.any { it.isBlank() || it.any(Char::isISOControl) }
                ) {
                    throw GradleException("kiteSsot { ios { sync { targets } } } must contain unique, non-blank names without controls.")
                }
                if (iosTargetNames.size > 1 && ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) {
                    throw GradleException(
                        "kiteSsot refuses to assign one Apple bundle identifier to multiple application targets " +
                            "(${iosTargetNames.joinToString()}). Select one target, or set propagate { bundleId = false }."
                    )
                }
            }
            ext.effectiveJvmTarget.orNull?.let(::validateJavaVersion)
            if (ext.effectiveNativeOptInsEnabled.get()) {
                val interopProjects = ext.effectiveNativeOptInProjects.get()
                ext.effectiveNativeOptInMarkers.get().forEach(::validateOptInMarker)
                interopProjects.forEach { validateGradleProjectPath(it, "nativeOptIns { projects }") }
                if (interopProjects.distinct().size != interopProjects.size) {
                    throw GradleException("kiteSsot { nativeOptIns { projects } } contains duplicate project paths.")
                }
            }
            // The locale and shared-project checks moved to projectsEvaluated: both can
            // be satisfied by sole-KMP detection, whose census completes only there.
            if (ext.effectiveBuildConfigEnabled.get() && ext.buildConfig.includeIdentity.get()) {
                val missing = buildList {
                    if (!ext.effectiveAppName.isPresent) add("appName")
                    if (!ext.effectiveVersion.isPresent) add("version")
                    if (!ext.effectiveAndroidVersionCode.isPresent) add("version or android { versionCode }")
                    if (!ext.effectiveAppId.isPresent) add("appId")
                }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "kiteSsot buildConfig.includeIdentity requires a complete identity; missing " +
                            missing.joinToString() + ". Set includeIdentity=false for fields-only generation."
                    )
                }
            }
            if (ext.effectiveSyncIos.get() && ext.effectivePropagateSharedModule.get()) {
                if (!ext.effectiveIosSharedModuleName.isPresent || !ext.effectiveIosPreviousSharedModuleName.isPresent) {
                    throw GradleException(
                        "kiteSsot shared-module migration needs both names. Call " +
                            "ios { sync { renameSharedModule(from = \"old\", to = \"new\") } }. " +
                            "Automatic Pod/Swift rename inference is disabled."
                    )
                }
                runCatching {
                    requireSwiftModuleIdentifier(ext.effectiveIosSharedModuleName.get(), "renameSharedModule(to)")
                    requireSwiftModuleIdentifier(ext.effectiveIosPreviousSharedModuleName.get(), "renameSharedModule(from)")
                }.getOrElse { failure ->
                    throw GradleException(failure.message ?: "Invalid iOS shared-module migration identifier", failure)
                }
            }
            // Safe-zone ratio sanity.
            if (ext.effectivePropagateLogo.get() && ext.effectiveLogoSafeZone.isPresent) {
                validateLogoSafeZoneRatio(ext.effectiveLogoSafeZone.get())
            }
            // Logo: FG must be paired with exactly one BG source (PNG or color).
            // Only validate when logo propagation is on, otherwise a stray FG with
            // propagateLogo = false shouldn't fail the build.
            if (ext.effectivePropagateLogo.get()) {
                val fgSet = ext.effectiveLogoForeground.isPresent
                val bgSet = ext.effectiveLogoBackground.isPresent
                val bgColorSet = ext.effectiveLogoBackgroundColor.isPresent
                if (!fgSet && !bgSet && !bgColorSet) {
                    throw GradleException(
                        "kiteSsot { logo { } } requires foreground plus exactly one of " +
                            "background or backgroundColor."
                    )
                }
                if (bgSet && bgColorSet) {
                    throw GradleException(
                        "kiteSsot { logo { background } } and { logo { backgroundColor } } are " +
                                "mutually exclusive. Set exactly one."
                    )
                }
                if (fgSet && !bgSet && !bgColorSet) {
                    throw GradleException(
                        "kiteSsot { logo { foreground } } is set but no background. Set either " +
                                "logo { background } or logo { backgroundColor }."
                    )
                }
                if (!fgSet && (bgSet || bgColorSet)) {
                    throw GradleException(
                        "kiteSsot { logo } has a background but no foreground. Set " +
                                "logo { foreground }, or remove the background."
                    )
                }
                if (bgColorSet) validateLogoBackgroundColorHex(ext.effectiveLogoBackgroundColor.get())
                if (ext.effectiveSyncIos.get()) {
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
            if (ext.effectiveTakeOverLegacyIcons.get()) {
                if (!ext.effectivePropagateLogo.get() || !ext.effectiveLogoForeground.isPresent ||
                    (!ext.effectiveLogoBackground.isPresent && !ext.effectiveLogoBackgroundColor.isPresent)
                ) {
                    throw GradleException(
                        "kiteSsot { logo { takeOverLegacyIcons } } requires a complete replacement " +
                            "logo plan: a foreground plus exactly one background."
                    )
                }
            }
            // Leaving logo { } unconfigured turns desktop icons off quietly
            // (effectiveDesktopIcons), so this only fires for an explicit icons = true.
            if (ext.desktop.icons.orNull == true && !ext.effectivePropagateLogo.get()) {
                throw GradleException(
                    "kiteSsot { desktop { icons = true } } needs a logo { } block with a foreground and " +
                        "exactly one of background or backgroundColor. Configure logo { }, or remove " +
                        "desktop { icons }.",
                )
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
        val detectedComposeProjects = linkedSetOf<String>()
        target.allprojects {
            val consumerProject = this
            // Registered while the ROOT project is still evaluating, which is what puts
            // it ahead of the afterEvaluate the Compose plugin registers when this
            // subproject applies it. Gradle runs afterEvaluate callbacks in registration
            // order, and Compose reads its plain `var` identity fields inside its own
            // callback, so second place means every desktop identity write is ignored.
            // Moving this inside plugins.withId("org.jetbrains.compose") would do exactly
            // that: withId fires after Compose's apply() has already returned. Leave it here.
            consumerProject.afterEvaluate {
                // Compose DSL signatures mention Kotlin Gradle plugin types, so Compose
                // being visible is not on its own enough to make these calls safe.
                if (!COMPOSE_ON_CLASSPATH || !KGP_ON_CLASSPATH) return@afterEvaluate
                if (!ext.effectiveDesktopEnabled.get()) return@afterEvaluate
                DesktopWiring.write(consumerProject, ext, isResilientDiagnosticInvocation(target))
            }
            plugins.withId("org.jetbrains.compose") {
                detectedComposeProjects += consumerProject.path
            }
            // The Android wiring runs on the diagnostic invocations too, unlike the KGP
            // wiring below: AGP validates its DSL on every invocation, so a consumer whose
            // compileSdk lives only in kiteSsot { } would fail configuration before the
            // diagnostic task could report anything. The adapters guard each value group
            // themselves on resilient runs and skip a failing provider instead of aborting.
            plugins.withId("com.android.application") {
                detectedAndroidApplications += consumerProject.path
                detectedAndroidProjects += consumerProject.path
                val resilient = isResilientDiagnosticInvocation(target)
                if (agpAdaptersUsable) {
                    if (useAgp8ClassicAdapter) {
                        Agp8ClassicAndroidWiringBridge.wireApplication(consumerProject, ext, resilient)
                    } else {
                        ClassicAndroidWiring.wireApplication(consumerProject, ext, resilient)
                    }
                } else if (!AGP_ON_CLASSPATH) {
                    androidProjectsWithoutSharedClassloader += consumerProject.path
                }
            }
            plugins.withId("com.android.library") {
                detectedAndroidProjects += consumerProject.path
                val resilient = isResilientDiagnosticInvocation(target)
                if (agpAdaptersUsable) {
                    if (useAgp8ClassicAdapter) {
                        Agp8ClassicAndroidWiringBridge.wireLibrary(consumerProject, ext, resilient)
                    } else {
                        ClassicAndroidWiring.wireLibrary(consumerProject, ext, resilient)
                    }
                } else if (!AGP_ON_CLASSPATH) {
                    androidProjectsWithoutSharedClassloader += consumerProject.path
                }
            }
            // AGP's KMP-native Android library plugin (com.android.kotlin.multiplatform.library)
            // exposes a different extension type than the classic com.android.library, so it needs
            // its own wiring. Common for the shared module in modern KMP setups (composeApp/shared).
            plugins.withId("com.android.kotlin.multiplatform.library") {
                detectedAndroidProjects += consumerProject.path
                val resilient = isResilientDiagnosticInvocation(target)
                if (agpAdaptersUsable) {
                    if (!KmpAndroidLibraryWiring.apply(consumerProject, ext, resilient)) {
                        kmpAndroidProjectsWithoutComponents += consumerProject.path
                    }
                } else if (!AGP_ON_CLASSPATH) {
                    androidProjectsWithoutSharedClassloader += consumerProject.path
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
                    // `kotlin { js() … }` body runs. The targets container is still empty
                    // there. wireWebIoWorker snapshots targets (unlike the lazy matching{}
                    // hooks above), so defer it to afterEvaluate.
                    consumerProject.afterEvaluate {
                        ext.effectiveJvmTarget.orNull?.let { javaVersion ->
                            val targetVersion = org.gradle.api.JavaVersion
                                .toVersion(validateJavaVersion(javaVersion))
                                .toString()
                            KotlinJvmTargetWiring.apply(consumerProject, targetVersion)
                        }
                        // wireWebIoWorker and wireBuildConfig moved to projectsEvaluated:
                        // both select their project against the shared module, which may
                        // only be KNOWN there when it comes from sole-KMP detection.
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
                            ext.effectiveJvmTarget.orNull?.let { javaVersion ->
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
            // The KMP census is complete here, so a sole candidate becomes the shared
            // module. Pure bookkeeping, safe ahead of the diagnostic early-return; the
            // orElse chain keeps any explicit choice in front of it.
            detectedKmpProjects.singleOrNull()?.let { ext.detectedSharedProject.set(it) }
            ext.detectedSharedProject.finalizeValue()
            val diagnosticSelection = runCatching { ext.effectiveAndroidApps.get() }.getOrDefault(emptyList())
            val diagnosticApplications = diagnosticSelection.ifEmpty { detectedAndroidApplications.toList() }
            val detectedDirectories = diagnosticApplications.mapNotNull { path ->
                target.findProject(path)?.layout?.projectDirectory?.asFile
            }
            val explicitAndroidDirectory =
                runCatching { ext.effectiveAndroidAppDirectory.orNull?.asFile }.getOrNull()
            val manifestDirectories = detectedDirectories.ifEmpty {
                listOfNotNull(explicitAndroidDirectory)
            }
            val resourceDirectories = listOfNotNull(explicitAndroidDirectory).ifEmpty {
                detectedDirectories
            }
            verifyTask.configure {
                androidApplicationProjects.set(diagnosticApplications)
                androidAppDirectories.set(manifestDirectories.map { it.path })
            }
            fun diagnosticBoolean(value: () -> Boolean): Boolean = runCatching(value).getOrDefault(false)
            val diagnosticNeedsAndroidIntegration = detectedAndroidProjects.isNotEmpty() && (
                diagnosticBoolean { ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent } ||
                    diagnosticBoolean { ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent } ||
                    diagnosticBoolean {
                        ext.effectivePropagateVersion.get() &&
                            (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)
                    } ||
                    diagnosticBoolean { ext.effectiveApplySdkLevels.get() && listOf(
                        ext.android.compileSdk,
                        ext.android.minSdk,
                        ext.android.targetSdk,
                        ext.android.ndk,
                    ).any { it.isPresent } } ||
                    diagnosticBoolean { ext.effectiveJvmTarget.isPresent } ||
                    diagnosticBoolean { ext.effectiveFilterAndroidResources.get() }
                )
            val diagnosticNeedsKgpIntegration =
                diagnosticBoolean { ext.effectiveNativeOptInsEnabled.get() } ||
                    diagnosticBoolean { ext.effectiveIoWorkerEnabled.get() } ||
                    diagnosticBoolean { ext.effectiveBuildConfigEnabled.get() } ||
                    (diagnosticBoolean { ext.effectiveJvmTarget.isPresent } &&
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
            if (detectedAndroidProjects.isNotEmpty() && ext.effectiveApplySdkLevels.get()) {
                validateSdkLevels(
                    ext.android.compileSdk.orNull,
                    ext.android.minSdk.orNull,
                    ext.android.targetSdk.orNull,
                )
                ext.android.ndk.orNull?.let(::validateNdkVersion)
            }
            if (detectedAndroidApplications.isNotEmpty() && ext.effectivePropagateVersion.get()) {
                ext.android.publishedVersionCode.orNull?.let { published ->
                    validatePublishedVersionCode(ext.effectiveAndroidVersionCode.orNull, published)
                }
            }
            if (ext.effectiveSyncIos.get() && ext.effectivePropagateVersion.get()) {
                ext.ios.publishedBuildNumber.orNull?.let { published ->
                    validatePublishedBuildNumber(ext.effectiveIosBuildNumber.orNull, published)
                }
            }
            val needsApplicationSelection =
                (ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) ||
                    (ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) ||
                    (ext.effectivePropagateVersion.get() &&
                        (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)) ||
                    ext.effectiveFilterAndroidResources.get()
            val usesApplicationSelection = needsApplicationSelection || ext.effectivePropagateLogo.get() ||
                ext.effectiveTakeOverLegacyIcons.get()
            val selectedApplications = if (usesApplicationSelection) {
                ext.effectiveAndroidApps.get()
            } else {
                emptyList()
            }
            when {
                selectedApplications.isEmpty() && detectedAndroidApplications.size > 1 && needsApplicationSelection ->
                    throw GradleException(
                        "kiteSsot found multiple Android application projects while app-scoped values are enabled: " +
                            "${detectedAndroidApplications.joinToString()}. Select the intended targets " +
                            "explicitly with modules { androidApps(\":app\")."
                    )

                selectedApplications.isNotEmpty() -> {
                    val unknown = selectedApplications.toSet() - detectedAndroidApplications
                    if (unknown.isNotEmpty()) {
                        throw GradleException(
                            "kiteSsot { modules { androidApps } } contains project paths that do not " +
                                "apply com.android.application: ${unknown.sorted().joinToString()}."
                        )
                    }
                    if (selectedApplications.size > 1 && ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) {
                        throw GradleException(
                            "kiteSsot refuses to assign one Android applicationId to multiple selected apps " +
                                "(${selectedApplications.joinToString()}). Select one app, or set " +
                                "propagate { bundleId = false } and give each app module its own id."
                        )
                    }
                }
            }

            val effectiveApplications = selectedApplications.ifEmpty { detectedAndroidApplications.toList() }
            if ((ext.effectivePropagateLogo.get() || ext.effectiveTakeOverLegacyIcons.get()) &&
                effectiveApplications.size > 1
            ) {
                throw GradleException(
                    "kiteSsot Android logo installation has one output sink but resolved multiple " +
                        "Android application projects: ${effectiveApplications.joinToString()}. " +
                        "Select exactly one with modules { androidApps(\":app\")."
                )
            }

            val resolvedApplicationPath = selectedApplications.singleOrNull()
                ?: detectedAndroidApplications.singleOrNull()
            // Fall back to the root directory rather than guessing a folder name:
            // 3.0 detects the application project instead of assuming "androidApp".
            val detectedDirectory = resolvedApplicationPath
                ?.let(target::findProject)
                ?.layout
                ?.projectDirectory
                ?: target.layout.projectDirectory
            resolvedAndroidAppDirectory.set(ext.effectiveAndroidAppDirectory.orElse(detectedDirectory))
            resolvedAndroidAppDirectory.finalizeValue()
            // The root directory is only a legitimate sink when the root really is the
            // Android application, or when the build named a directory outright.
            androidOutputApproved.set(
                ext.effectiveAndroidAppDirectory.isPresent ||
                    resolvedApplicationPath != null ||
                    target.plugins.hasPlugin("com.android.application"),
            )
            androidOutputApproved.finalizeValue()

            val needsKgpIntegration = ext.effectiveNativeOptInsEnabled.get() ||
                ext.effectiveIoWorkerEnabled.get() || ext.effectiveBuildConfigEnabled.get() ||
                (ext.effectiveJvmTarget.isPresent &&
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
                (ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) ||
                    (ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) ||
                    (ext.effectivePropagateVersion.get() &&
                        (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)) ||
                    (ext.effectiveApplySdkLevels.get() && listOf(
                        ext.android.compileSdk,
                        ext.android.minSdk,
                        ext.android.targetSdk,
                        ext.android.ndk,
                    ).any { it.isPresent }) ||
                    ext.effectiveJvmTarget.isPresent || ext.effectiveFilterAndroidResources.get()
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
                            "Supported AGP range is 8.5.2 through 9.3.x."
                    )
                if (!isSupportedAgpVersion(agpVersion)) {
                    throw GradleException(
                        "[KITESSOT-COMPAT-002] Unsupported Android Gradle plugin $agpVersion; " +
                            "this kitessot build supports AGP 8.5.2 through 9.3.x."
                    )
                }
            }
            val needsKmpAndroidSdk = ext.effectiveApplySdkLevels.get() &&
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

            // Desktop selection needs the whole project census, so it lands here rather
            // than in the per-project afterEvaluate that writes the identity values.
            if (ext.effectiveDesktopEnabled.get() && detectedComposeProjects.isNotEmpty()) {
                if (!COMPOSE_ON_CLASSPATH || !KGP_ON_CLASSPATH) {
                    throw GradleException(
                        "[KITESSOT-COMPAT-007] kiteSsot cannot configure requested desktop values in " +
                            "${detectedComposeProjects.joinToString()}: Compose Gradle plugin classes are " +
                            "isolated in a sibling classloader. Declare org.jetbrains.compose and " +
                            "kotlin(\"multiplatform\") with apply false in the root plugins block so Compose " +
                            "and kitessot share a classloader. No requested desktop value was silently skipped."
                    )
                }
                val selectedDesktopApps = ext.effectiveDesktopApps.get()
                selectedDesktopApps.forEach { validateGradleProjectPath(it, "modules { desktopApps }") }
                if (selectedDesktopApps.distinct().size != selectedDesktopApps.size) {
                    throw GradleException("kiteSsot { modules { desktopApps } } contains duplicate project paths.")
                }
                if (selectedDesktopApps.isEmpty()) {
                    val detectedDesktopApps = detectedComposeProjects.filter { path ->
                        target.findProject(path)?.let(DesktopWiring::isDesktopApp) == true
                    }
                    if (detectedDesktopApps.size > 1) {
                        throw GradleException(
                            "kiteSsot found multiple Compose Desktop application projects while desktop " +
                                "values are enabled: ${detectedDesktopApps.joinToString()}. Select the " +
                                "intended targets explicitly with modules { desktopApps(\":desktopApp\") }."
                        )
                    }
                } else {
                    val unknown = selectedDesktopApps.toSet() - detectedComposeProjects
                    if (unknown.isNotEmpty()) {
                        throw GradleException(
                            "kiteSsot { modules { desktopApps } } contains project paths that do not apply " +
                                "org.jetbrains.compose: ${unknown.sorted().joinToString()}."
                        )
                    }
                    // Whether a selected path is genuinely a desktop app is checked in
                    // DesktopWiring.write() itself, before that path's own afterEvaluate ever
                    // touches desktop.application: by the time this project census runs, every
                    // explicitly selected project has already been written and its initialization
                    // flags would read APPLICATION regardless, so the check cannot live here.
                }
            }

            val selectedSharedProject = ext.effectiveSharedProjectPath.orNull
            if (ext.effectiveBuildConfigEnabled.get() && selectedSharedProject == null) {
                throw GradleException(
                    when (detectedKmpProjects.size) {
                        0 -> "kiteSsot buildConfig is enabled but no shared project is selected, and no " +
                            "project applying org.jetbrains.kotlin.multiplatform was detected. Set " +
                            "modules { shared = \":shared\" }."
                        else -> "kiteSsot buildConfig is enabled but no shared project is selected, and " +
                            "${detectedKmpProjects.size} projects apply org.jetbrains.kotlin.multiplatform: " +
                            "${detectedKmpProjects.sorted().joinToString()}. Pick one with modules { shared }."
                    }
                )
            }
            if (ext.effectiveBuildConfigEnabled.get() && selectedSharedProject !in detectedKmpProjects) {
                throw GradleException(
                    "kiteSsot buildConfig project '\$selectedSharedProject' does not apply " +
                        "org.jetbrains.kotlin.multiplatform."
                )
            }
            if (ext.effectiveFilterAndroidResources.get() && ext.canonicalLocales.get().isEmpty()) {
                throw GradleException(
                    "kiteSsot { android { filterResourcesToLocales } } requires at least one locale. " +
                        "Configure locales explicitly or add a supported values-<locale> resource directory."
                )
            }
            // Shared-scoped generation, wired once the shared module is settled. Each
            // function still self-selects (web can name projects beyond the shared one),
            // so simply offer every detected KMP project.
            if (kgpAdaptersUsable) {
                detectedKmpProjects.forEach { path ->
                    target.findProject(path)?.let { kmpProject ->
                        wireWebIoWorker(kmpProject, ext)
                        wireBuildConfig(kmpProject, ext)
                    }
                }
            }
            if (ext.effectiveNativeOptInsEnabled.get()) {
                val interopProjects = ext.effectiveNativeOptInProjects.get().ifEmpty {
                    listOfNotNull(selectedSharedProject)
                }
                if (interopProjects.isEmpty()) {
                    throw GradleException(
                        "kiteSsot interop opt-ins need an explicit KMP scope. Set " +
                            "nativeOptIns { projects(\":shared\") } or modules { shared = \":shared\" }."
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
            if (ext.effectiveIoWorkerEnabled.get()) {
                val webProjects = ext.effectiveIoWorkerProjects.get()
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
                            "web { ioWorker { projects(\":shared\") } } or modules { shared = \":shared\" }."
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
     * targets, where the markers resolve. Elsewhere they are absent and harmless.
     */
    private fun propagateInteropOptIns(project: Project, ext: KiteSsotExtension) {
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val markers = project.provider {
            val selectedProjects = ext.effectiveNativeOptInProjects.get().ifEmpty {
                listOfNotNull(ext.effectiveSharedProjectPath.orNull)
            }
            if (ext.effectiveNativeOptInsEnabled.get() && project.path in selectedProjects) {
                interopOptIns(ext.effectiveNativeOptInMarkers.get(), ext.effectiveNativeOptInBuiltIns.get())
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
        if (!ext.effectiveIoWorkerEnabled.get()) return
        val selectedProjects = ext.effectiveIoWorkerProjects.get().ifEmpty {
            listOfNotNull(ext.effectiveSharedProjectPath.orNull)
        }
        if (project.path !in selectedProjects) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val requestedNames = ext.effectiveIoWorkerTargets.get()
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

        // Validate the destination package early. A malformed value would otherwise
        // appear as a confusing compile error inside the generated file.
        val pkg = ext.effectiveIoWorkerPackage.get()
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
                workerPackage.set(ext.effectiveIoWorkerPackage)
                outputDir.set(genDir)
                dryRun.set(ext.effectiveDryRun)
            }
            // srcDir(taskProvider.flatMap { output }) carries the task dependency to
            // EVERY consumer of the source set (compile, sourcesJar, dokka, IDE
            // import), not just a name-matched compile task.
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
        if (!ext.effectiveBuildConfigEnabled.get()) return
        if (!ext.effectiveSharedProjectPath.isPresent || project.path != ext.effectiveSharedProjectPath.get()) return
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
            appName.set(ext.effectiveAppName.orElse(""))
            versionName.set(ext.effectiveVersion.orElse(""))
            versionCode.set(ext.effectiveAndroidVersionCode.orElse(0))
            androidApplicationId.set(ext.androidApplicationId.orElse(""))
            iosBundleId.set(ext.iosBundleId.orElse(""))
            locales.set(ext.canonicalLocales)
            identityInputs.set(project.provider {
                if (!cfg.includeIdentity.get()) {
                    emptyList()
                } else {
                    listOf(
                        ext.effectiveAppName.orNull.orEmpty(),
                        ext.effectiveVersion.orNull.orEmpty(),
                        ext.effectiveAndroidVersionCode.orNull?.toString().orEmpty(),
                        ext.androidApplicationId.orNull.orEmpty(),
                        ext.iosBundleId.orNull.orEmpty(),
                    ) + ext.canonicalLocales.get()
                }
            })
            customFields.set(cfg.fields)
            outputDir.set(genDir)
            dryRun.set(ext.effectiveDryRun)
        }
        kmp.sourceSets.matching { it.name == "commonMain" }.configureEach {
            kotlin.srcDir(genTask.flatMap { it.outputDir })
        }
    }

    // --- Locale auto-detection ----------------------------------------------

    private fun autoDetectLocales(root: Project, ext: KiteSsotExtension): List<String> {
        val composeRes = when {
            ext.effectiveComposeResources.isPresent -> ext.effectiveComposeResources.get().asFile
            ext.effectiveSharedProjectPath.isPresent -> {
                val sharedProject = root.findProject(ext.effectiveSharedProjectPath.get()) ?: return emptyList()
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
            val runCondition = ext.effectiveSyncIos.zip(ext.effectiveSanitizeIosProject) { a, b -> a && b }
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            infoPlistFile.set(ext.effectiveIosInfoPlist)
            propagateAppName.set(ext.effectiveAppName.map { ext.effectivePropagateAppName.get() }.orElse(false))
            propagateMarketingVersion.set(
                ext.effectiveIosMarketingVersion.map { ext.effectivePropagateVersion.get() }.orElse(false)
            )
            propagateBuildNumber.set(
                ext.effectiveIosBuildNumber.map { ext.effectivePropagateVersion.get() }.orElse(false)
            )
            usesNonExemptEncryption.set(ext.effectiveNonExemptEncryption)
            proMotion120Hz.set(ext.effectiveProMotion)
            conflictPolicy.set(ext.effectivePlistConflictPolicy)
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncIosTask(
        root: Project,
        ext: KiteSsotExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("kiteSsotSyncIosConfig") {
            val runCondition = ext.effectiveSyncIos
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            pbxprojFile.set(ext.effectiveIosPbxproj)
            infoPlistFile.set(ext.effectiveIosInfoPlist)
            podfile.set(ext.effectiveIosPodfile)
            iosAppDir.set(ext.effectiveIosAppDirectory)
            appiconsetDir.set(ext.effectiveIosAppIconDirectory)
            marketingVersion.set(ext.effectiveIosMarketingVersion)
            buildNumber.set(ext.effectiveIosBuildNumber)
            appName.set(ext.effectiveAppName)
            bundleId.set(ext.iosBundleId)
            locales.set(ext.canonicalLocales)
            targetNames.set(ext.effectiveIosTargets)
            iosSharedModuleName.set(ext.effectiveIosSharedModuleName)
            iosPreviousSharedModuleName.set(ext.effectiveIosPreviousSharedModuleName)
            propagateVersion.set(ext.effectivePropagateVersion)
            propagateAppName.set(ext.effectiveAppName.map { ext.effectivePropagateAppName.get() }.orElse(false))
            propagateBundleId.set(ext.effectiveAppId.map { ext.effectivePropagateBundleId.get() }.orElse(false))
            propagateLocaleList.set(
                ext.canonicalLocales.map { it.isNotEmpty() && ext.effectivePropagateLocales.get() }
            )
            propagateSharedModule.set(ext.effectivePropagateSharedModule)
            propagateLogo.set(ext.effectivePropagateLogo)
            sanitizeSourcePlist.set(ext.effectiveSanitizeIosProject)
            usesNonExemptEncryption.set(ext.effectiveNonExemptEncryption)
            proMotion120Hz.set(ext.effectiveProMotion)
            plistConflictPolicy.set(ext.effectivePlistConflictPolicy)
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncIosLogoTask(
        root: Project,
        ext: KiteSsotExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("kiteSsotSyncIosLogo") {
            val runCondition = ext.effectiveSyncIos.zip(ext.effectivePropagateLogo) { a, b -> a && b }
            onlyIf { runCondition.get() }
            foregroundPng.set(ext.effectiveLogoForeground)
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.set(ext.effectiveLogoBackground)
            backgroundColorHex.set(ext.effectiveLogoBackgroundColor)
            appiconsetDir.set(ext.effectiveIosAppIconDirectory)
            outputFiles.from(ext.effectiveIosAppIconDirectory.map { dir -> SyncIosLogoTask.OUTPUT_FILE_NAMES.map { dir.file(it) } })
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncAndroidLogoTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        androidOutputApproved: Provider<Boolean>,
    ): TaskProvider<SyncAndroidLogoTask> =
        root.tasks.register<SyncAndroidLogoTask>("kiteSsotSyncAndroidLogo") {
            val runCondition = ext.effectivePropagateLogo
            onlyIf { runCondition.get() }
            foregroundPng.set(ext.effectiveLogoForeground)
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.set(ext.effectiveLogoBackground)
            backgroundColorHex.set(ext.effectiveLogoBackgroundColor)
            safeZoneRatio.set(ext.effectiveLogoSafeZone)
            emitMonochrome.set(ext.android.compileSdk.map { it >= 33 }.orElse(false))
            cleanupLegacyArtifacts.set(ext.effectiveTakeOverLegacyIcons)
            outputSinkApproved.set(androidOutputApproved)
            dryRun.set(ext.effectiveDryRun)
            // Resolve lazily: androidAppModule may not be set yet at register time.
            val resDir = resolvedAndroidAppDirectory.dir("src/main/res")
            androidResDir.set(resDir)
            outputFiles.from(resDir.map { dir -> SyncAndroidLogoTask.OUTPUT_RELATIVE_PATHS.map { dir.file(it) } })
        }

    private fun registerCleanupLegacyLogoTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        androidOutputApproved: Provider<Boolean>,
    ): TaskProvider<CleanupLegacyAppLogoArtifactsTask> =
        root.tasks.register<CleanupLegacyAppLogoArtifactsTask>("kiteSsotCleanupLegacyAppLogoArtifacts") {
            val runCondition = ext.effectiveTakeOverLegacyIcons
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            dryRun.set(ext.effectiveDryRun)
            outputSinkApproved.set(androidOutputApproved)
            androidResDir.set(resolvedAndroidAppDirectory.dir("src/main/res"))
        }

    private fun registerVerifyTask(
        root: Project,
        ext: KiteSsotExtension,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteSsotVerifyTask> =
        root.tasks.register<KiteSsotVerifyTask>("kiteSsotVerify") {
            colorEnabled.set(colorSupported)
            projectRootDir.set(root.layout.projectDirectory)
            appName.set(ext.effectiveAppName)
            versionName.set(ext.effectiveVersion)
            versionCode.set(root.resilientValue { ext.effectiveAndroidVersionCode.orNull })
            androidApplicationId.set(ext.androidApplicationId)
            iosBundleId.set(ext.iosBundleId)
            locales.set(ext.canonicalLocales)
            iosSharedModuleName.set(ext.effectiveIosSharedModuleName)
            pbxprojFile.set(ext.effectiveIosPbxproj)
            infoPlistFile.set(ext.effectiveIosInfoPlist)
            podfile.set(ext.effectiveIosPodfile)
            compileSdk.set(ext.android.compileSdk)
            minSdk.set(ext.android.minSdk)
            targetSdk.set(ext.android.targetSdk)
            ndkVersion.set(ext.android.ndk)
            javaVersion.set(ext.effectiveJvmTarget)
            propagateInteropOptIns.set(ext.effectiveNativeOptInsEnabled)
            generateIoWorker.set(ext.effectiveIoWorkerEnabled)
            logoForeground.set(ext.effectiveLogoForeground.map { true }.orElse(false))
            logoBackground.set(ext.effectiveLogoBackground.map { true }.orElse(false))
            logoBackgroundColor.set(ext.effectiveLogoBackgroundColor)
            desktopBundleId.set(root.resilientValue { ext.desktopBundleId.orNull })
            desktopBuildNumber.set(root.resilientValue { ext.effectiveDesktopBuildNumber.orNull })
            desktopIcons.set(ext.effectiveDesktopIcons)
        }

    private fun registerDoctorTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteSsotDoctorTask> =
        root.tasks.register<KiteSsotDoctorTask>("kiteSsotDoctor") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory, colorSupported)
        }

    private fun registerCheckTask(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteSsotCheckTask> =
        root.tasks.register<KiteSsotCheckTask>("kiteSsotCheck") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory, colorSupported)
        }

    private fun registerPlanTask(
        root: Project,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteSsotPlanTask> =
        root.tasks.register<KiteSsotPlanTask>("kiteSsotPlan") {
            colorEnabled.set(colorSupported)
        }

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
                    if (ext.effectiveSyncIos.get() && ext.effectiveSanitizeIosProject.get()) {
                        add("sanitize source Info.plist in the iOS text transaction")
                    }
                    if (ext.effectiveSyncIos.get()) add("migrate selected Xcode build settings")
                    if (ext.effectiveSyncIos.get() && ext.effectivePropagateSharedModule.get()) {
                        add("migrate explicit Pod/Swift module references")
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectivePropagateLogo.get()) add("install iOS AppIcon assets")
                    if (ext.effectivePropagateLogo.get()) add("install Android launcher assets")
                    if (ext.effectiveTakeOverLegacyIcons.get()) {
                        add("transactionally take over legacy Android logo artifacts")
                    }
                }
            })
            mutationPaths.set(root.provider {
                buildList {
                    fun addTextTarget(file: java.io.File) {
                        add(file.path)
                        if (ext.effectiveBackups.get()) add(file.path + BACKUP_SUFFIX)
                    }
                    if (ext.effectiveSyncIos.get()) {
                        addTextTarget(ext.effectiveIosPbxproj.get().asFile)
                        add(root.layout.projectDirectory.file(".gradle/kitessot/rewrite.lock").asFile.path)
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectiveSanitizeIosProject.get()) {
                        addTextTarget(ext.effectiveIosInfoPlist.get().asFile)
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectivePropagateSharedModule.get()) {
                        addTextTarget(ext.effectiveIosPodfile.get().asFile)
                        add(ext.effectiveIosAppDirectory.get().asFile.path)
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectivePropagateLogo.get()) {
                        val icons = ext.effectiveIosAppIconDirectory.get().asFile
                        add(icons.path)
                        val identity = SyncIosLogoTask.catalogIdentity(root.projectDir, icons)
                        val metadata = (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
                            .resolve(".kitessot/$identity")
                        val manifest = metadata.resolve("owned-files-v1")
                        add(manifest.path)
                        add(manifest.path + ".lock")
                        if (ext.effectiveBackups.get()) {
                            add(root.projectDir.resolve(".kitessot/recovery/ios-appicon/$identity").path)
                        }
                    }
                    if (ext.effectivePropagateLogo.get() || ext.effectiveTakeOverLegacyIcons.get()) {
                        androidResourceDirectories.forEach { res ->
                            add(res.path)
                            val manifest = res.parentFile.resolve(".kitessot/android-logo-owned-files-v1")
                            add(manifest.path)
                            add(manifest.path + ".lock")
                        }
                    }
                    if (ext.effectiveTakeOverLegacyIcons.get()) {
                        val recovery = root.projectDir.resolve(".kitessot/recovery/android-logo")
                        add(recovery.path)
                        add(recovery.resolve("removal-provenance.tsv").path)
                        add(recovery.resolve(".migration.lock").path)
                    }
                }
            })
            selectedTargets.set(root.provider {
                ext.effectiveAndroidApps.get().ifEmpty { detectedApplications }
                    .map { "Android project $it" } +
                    ext.effectiveIosTargets.get().map { "Xcode target $it" }
            })
            policies.set(root.provider {
                mapOf(
                    "backups" to ext.effectiveBackups.get().toString(),
                    "dryRun" to ext.effectiveDryRun.get().toString(),
                    "ios.sync.onConflict" to ext.effectivePlistConflictPolicy.get().name,
                    "ios.deploymentTarget" to ext.ios.deploymentTarget.orNull.orEmpty(),
                    "pbxprojScope" to if (ext.effectiveIosTargets.get().isEmpty()) {
                        "sole application target only"
                    } else {
                        "explicit targets"
                    },
                    "sourceMutationDuringBuild" to "disabled",
                )
            })
            exactChanges.set(root.provider {
                buildList {
                    if (ext.effectiveSyncIos.get()) {
                        add(
                            "iOS text transaction: calculate target-scoped unified diffs from " +
                                ext.effectiveIosPbxproj.get().asFile.path,
                        )
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectiveSanitizeIosProject.get()) {
                        add(
                            "Info.plist: include the configured SSOT references/flags under " +
                                "${ext.effectivePlistConflictPolicy.get()} policy",
                        )
                    }
                    if (ext.effectiveSyncIos.get() && ext.effectivePropagateLogo.get()) {
                        add(
                            "iOS AppIcon: align the selected target's existing catalog setting, then render " +
                                "AppIcon-1024.png and Contents.json after ownership validation",
                        )
                    }
                    if (ext.effectivePropagateLogo.get()) {
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

    /**
     * Wrap a provider that may throw (a scheme-derived version code or build
     * number) so a resilient diagnostic task can bind it without ever failing
     * configuration-cache serialization. A thrown [GradleException] becomes an
     * absent value; the diagnostics engine already reports that as a finding.
     */
    private fun <T : Any> Project.resilientValue(compute: () -> T?): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return provider { runCatching(compute).getOrNull() } as Provider<T>
    }

    private fun KiteSsotDiagnosticTaskBase.bindDiagnosticInputs(
        root: Project,
        ext: KiteSsotExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ) {
        colorEnabled.set(colorSupported)
        propagateAppName.set(ext.effectivePropagateAppName)
        appName.set(ext.effectiveAppName)
        propagateBundleId.set(ext.effectivePropagateBundleId)
        iosBundleId.set(ext.iosBundleId)
        propagateVersion.set(ext.effectivePropagateVersion)
        versionName.set(ext.effectiveVersion)
        hasVersionCodeOverride.set(ext.effectiveHasExplicitVersionCode)
        resolvedVersionCode.set(root.resilientValue { ext.effectiveAndroidVersionCode.orNull })
        propagateLocaleList.set(ext.effectivePropagateLocales)
        locales.set(ext.locales)
        filterAndroidResources.set(ext.effectiveFilterAndroidResources)
        syncIos.set(ext.effectiveSyncIos)
        sanitizeIosProject.set(ext.effectiveSanitizeIosProject)
        propagateLogo.set(ext.effectivePropagateLogo)
        cleanupLegacyLogoArtifacts.set(ext.effectiveTakeOverLegacyIcons)
        iosMarketingVersion.set(ext.effectiveIosMarketingVersion)
        iosBuildNumber.set(root.resilientValue { ext.effectiveIosBuildNumber.orNull })
        iosDeploymentTarget.set(ext.ios.deploymentTarget)
        usesNonExemptEncryption.set(ext.effectiveNonExemptEncryption)
        proMotion120Hz.set(ext.effectiveProMotion)
        plistConflictPolicy.set(ext.effectivePlistConflictPolicy)
        iosTargetNames.set(ext.effectiveIosTargets)
        androidApplicationProjects.set(ext.effectiveAndroidApps)
        manifestFile.set(resolvedAndroidAppDirectory.file("src/main/AndroidManifest.xml"))
        infoPlistFile.set(ext.effectiveIosInfoPlist)
        pbxprojFile.set(ext.effectiveIosPbxproj)
        appiconsetDir.set(ext.effectiveIosAppIconDirectory)
        androidResDir.set(resolvedAndroidAppDirectory.dir("src/main/res"))
        projectRootDir.set(root.layout.projectDirectory)
        androidEmitMonochrome.set(ext.android.compileSdk.map { it >= 33 }.orElse(false))
        androidLogoInputFingerprint.set(root.provider {
            if (!ext.effectivePropagateLogo.get()) {
                "disabled"
            } else {
                logoInputFingerprintForFiles(
                    rendererVersion = SyncAndroidLogoTask.RENDERER_FINGERPRINT_VERSION,
                    foreground = ext.effectiveLogoForeground.get().asFile,
                    background = ext.effectiveLogoBackground.orNull?.asFile,
                    backgroundColor = ext.effectiveLogoBackgroundColor.orNull,
                    parameters = mapOf(
                        "emitMonochrome" to (ext.android.compileSdk.orNull?.let { it >= 33 } == true).toString(),
                        "safeZoneRatio" to ext.effectiveLogoSafeZone.get().toString(),
                    ),
                )
            }
        })
        iosLogoInputFingerprint.set(root.provider {
            if (!ext.effectiveSyncIos.get() || !ext.effectivePropagateLogo.get()) {
                "disabled"
            } else {
                logoInputFingerprintForFiles(
                    rendererVersion = SyncIosLogoTask.RENDERER_FINGERPRINT_VERSION,
                    foreground = ext.effectiveLogoForeground.get().asFile,
                    background = ext.effectiveLogoBackground.orNull?.asFile,
                    backgroundColor = ext.effectiveLogoBackgroundColor.orNull,
                )
            }
        })
        agpOnClasspath.set(AGP_ON_CLASSPATH)
        if (AGP_ON_CLASSPATH) runtimeAgpVersion()?.let(this.activeAgpVersion::set)
        kgpOnClasspath.set(KGP_ON_CLASSPATH)
        if (KGP_ON_CLASSPATH) runtimeKgpVersion()?.let(this.activeKgpVersion::set)
        kgpRequired.set(
            root.provider {
                ext.effectiveNativeOptInsEnabled.get() || ext.effectiveIoWorkerEnabled.get() ||
                    ext.effectiveBuildConfigEnabled.get()
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

        /* Task lookups are restricted to the ROOT project's container, guarded by the
         * non-realizing `names` view. NEVER resolve subproject-qualified paths: this method
         * runs from `plugins.withId` callbacks, i.e. while a module's `plugins { }` block is
         * still executing, and AGP 9.2's KMP-native library plugin registers its compilation
         * tasks at apply time, so `findByName(":shared:compileAndroidMain")` would REALIZE
         * that task and observe the module's compile classpaths before its build script body
         * has run. Every later `dependencies { }` mutation in that script then fails with
         * "configuration was observed" (and `jvmToolchain { }` with "property 'languageVersion'
         * is final"). Subproject-qualified invocations still get plain name matching; only the
         * aggregate-alias dependency walk is root-only. */
        fun rootTask(path: String): Task? {
            val segments = path.removePrefix(":").split(':').filter(String::isNotBlank)
            if (segments.size != 1) return null // subproject-qualified: name matching only
            val requestedName = segments.single()
            val names = root.tasks.names
            val resolvedName = if (requestedName in names) {
                requestedName
            } else {
                names.filter { it.startsWith(requestedName) }.singleOrNull() ?: return null
            }
            return root.tasks.findByName(resolvedName)
        }

        fun resilient(task: Task, visiting: MutableSet<String>): Boolean {
            if (task.name in RESILIENT_DIAGNOSTIC_TASKS) return true
            if (!visiting.add(task.path)) return false
            val dependencies = runCatching { task.taskDependencies.getDependencies(task) }.getOrElse { return false }
            return dependencies.isNotEmpty() && dependencies.all { resilient(it, visiting.toMutableSet()) }
        }

        return requested.all { path ->
            path.substringAfterLast(':') in RESILIENT_DIAGNOSTIC_TASKS ||
                rootTask(path)?.let { resilient(it, mutableSetOf()) } == true
        }
    }

    /**
     * Freeze every validated DSL input before subprojects can observe it.
     *
     * `locales` alone is frozen lazily: its convention walks the shared module's
     * compose resources, and when the shared module comes from sole-KMP detection
     * it is only known at projectsEvaluated, after this freeze runs. Eager
     * finalization here would lock the list to empty before detection could speak.
     */
    private fun finalizeModel(ext: KiteSsotExtension) {
        modelValues(ext).forEach { value ->
            if (value === ext.locales) {
                value.finalizeValueOnRead()
                value.disallowChanges()
            } else {
                value.finalizeValue()
            }
        }
    }

    /** Prevent late mutation without realizing provider-backed diagnostic values. */
    private fun disallowModelChanges(ext: KiteSsotExtension) {
        modelValues(ext).forEach { it.disallowChanges() }
    }

    /**
     * Every configurable input behind the DSL, new and pre-3.0 alike.
     *
     * Derived `effective*` views are deliberately absent: they are read-only
     * providers over these values, so locking the sources locks the views too.
     */
    private fun modelValues(ext: KiteSsotExtension): List<HasConfigurableValue> =
        listOf(
            ext.appName, ext.version, ext.appId, ext.locales, ext.jvmTarget,
            ext.scheme, ext.dryRun, ext.backups,
            ext.logoConfigured, ext.nativeOptInsConfigured, ext.buildConfigConfigured,
        ) + listOf(
            ext.modules.shared, ext.modules.androidApps,
            ext.modules.androidAppDirectory, ext.modules.composeResources,
            ext.propagate.appName, ext.propagate.bundleId,
            ext.propagate.version, ext.propagate.locales,
        ) + listOf(
            ext.android.idSuffix, ext.android.versionCode, ext.android.rebuild,
            ext.android.scheme, ext.android.compileSdk, ext.android.minSdk,
            ext.android.targetSdk, ext.android.ndk, ext.android.publishedVersionCode,
            ext.android.applySdkLevels, ext.android.filterResourcesToLocales,
        ) + listOf(
            ext.ios.bundleIdSuffix, ext.ios.marketingVersion, ext.ios.buildNumber,
            ext.ios.rebuild, ext.ios.scheme, ext.ios.publishedBuildNumber,
            ext.ios.deploymentTarget, ext.ios.pbxproj, ext.ios.podfile,
            ext.ios.infoPlist, ext.ios.appDirectory, ext.ios.appIconDirectory,
        ) + listOf(
            ext.ios.sync.enabled, ext.ios.sync.configured, ext.ios.sync.targets,
            ext.ios.sync.sanitizePlist, ext.ios.sync.onConflict,
            ext.ios.sync.nonExemptEncryption, ext.ios.sync.proMotion,
            ext.ios.sync.previousSharedModuleName, ext.ios.sync.newSharedModuleName,
        ) + listOf(
            ext.logo.enabled, ext.logo.foreground, ext.logo.background,
            ext.logo.backgroundColor, ext.logo.androidSafeZone, ext.logo.takeOverLegacyIcons,
            ext.nativeOptIns.enabled, ext.nativeOptIns.builtIns,
            ext.nativeOptIns.markers, ext.nativeOptIns.projects,
        ) + listOf(
            ext.web.ioWorker.enabled, ext.web.ioWorker.configured,
            ext.web.ioWorker.targets, ext.web.ioWorker.projects, ext.web.ioWorker.packageName,
            ext.buildConfig.enabled, ext.buildConfig.packageName, ext.buildConfig.className,
            ext.buildConfig.includeIdentity, ext.buildConfig.allowBuildCache, ext.buildConfig.fields,
        ) + listOf(
            ext.desktop.enabled, ext.desktop.configured, ext.desktop.idSuffix,
            ext.desktop.buildNumber, ext.desktop.rebuild, ext.desktop.scheme,
            ext.desktop.publishedBuildNumber, ext.desktop.icons,
            ext.desktop.roundMacOsIcon, ext.desktop.linuxPackageName,
            ext.desktop.deriveUpgradeUuid, ext.modules.desktopApps,
        ) + legacyModelValues(ext)

    /** Pre-3.0 inputs, still honoured until 4.0 removes them. */
    private fun legacyModelValues(ext: KiteSsotExtension): List<HasConfigurableValue> =
        listOf(
            ext.versionName, ext.bundleIdBase, ext.javaVersion, ext.versionCodeOverride,
            ext.iosMarketingVersion, ext.iosBuildNumber, ext.iosBundleSuffix,
            ext.androidApplicationIdSuffix, ext.sharedProjectPath,
            ext.androidApplicationProjects, ext.androidAppDirectory,
            ext.composeResourcesDirectory, ext.iosSharedModuleName,
            ext.iosPreviousSharedModuleName,
        ) + listOf(
            ext.iosPbxprojFile, ext.iosPodfileFile, ext.iosInfoPlistFile,
            ext.iosAppDirectory, ext.iosAppIconDirectory,
            ext.appLogoPngForeground, ext.appLogoPngBackground,
            ext.appLogoBackgroundColor, ext.appLogoAndroidSafeZoneRatio,
            ext.cleanupLegacyLogoArtifacts,
        ) + listOf(
            ext.propagateAppName, ext.propagateBundleId, ext.propagateVersion,
            ext.propagateLocaleList, ext.propagateAndroidSdk, ext.filterAndroidResources,
            ext.propagateLogo, ext.syncIos, ext.sanitizeIosProject,
            ext.propagateSharedModule, ext.propagateInteropOptIns,
            ext.extraOptIns, ext.interopProjectPaths, ext.backupBeforeRewrite,
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
         * classloader. Calling into KGP-typed methods would then throw
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

        /**
         * Whether the (compileOnly) Compose Gradle plugin classes are loadable from
         * kitessot's own classloader. False when the consumer declares
         * org.jetbrains.compose only in a subproject, which puts it in a sibling
         * classloader.
         */
        internal val COMPOSE_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "org.jetbrains.compose.desktop.DesktopExtension",
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

        private fun runtimeComposeVersion(): String? = runCatching {
            Class.forName(
                "org.jetbrains.compose.ComposePlugin",
                false,
                KiteSsotPlugin::class.java.classLoader,
            ).`package`?.implementationVersion
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
