@file:Suppress("DEPRECATION")

package io.github.yuroyami.kiteconfig

import org.gradle.api.Action
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
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsSubTargetContainerDsl

/**
 * Root aggregation plugin for the `kiteConfig` application model.
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
/**
 * Whether the typed peer-plugin adapters may run for this consumer.
 *
 * [supported] answers "is this version on the tested matrix". [ignoreGuards] is
 * the user's explicit decision to run anyway. Every adapter branch asks this one
 * function, because a branch that consults [supported] alone leaves its
 * integration silently off while the console reports that the typed
 * integrations stayed active.
 */
internal fun typedIntegrationsActive(supported: Boolean, ignoreGuards: Boolean): Boolean =
    supported || ignoreGuards

public class KiteConfigPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        if (target != target.rootProject) {
            throw GradleException(
                "io.github.yuroyami.kiteconfig must be applied to the root project. " +
                        "Apply it in the root build.gradle.kts, not in a submodule."
            )
        }
        if (GradleVersion.current() < GradleVersion.version(MIN_GRADLE)) {
            throw GradleException(
                "kiteConfig requires Gradle $MIN_GRADLE or newer; current Gradle is " +
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

        val ext = target.extensions.create<KiteConfigExtension>("kiteConfig")
        ext.defaultIosInfoPlist.set(target.layout.projectDirectory.file("iosApp/iosApp/Info.plist"))
        ext.defaultIosPbxproj.set(
            target.layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj"),
        )

        // Nested DSL blocks. Gradle can't decorate an abstract property of a
        // non-managed type on KiteConfigExtension, so we create them explicitly and
        // expose them via getters on the parent.
        val extAware = ext as ExtensionAware
        extAware.extensions.create<KiteConfigModulesExtension>("modules").apply {
            androidApps.convention(emptyList())
            desktopApps.convention(emptyList())
        }

        extAware.extensions.create<KiteVersionScope>("versionScope")
        extAware.extensions.create<KiteJvmScope>("jvmScope")
        extAware.extensions.create<KiteLocalesScope>("localesScope")
        extAware.extensions.create<KiteSplashScope>("splash")
        val androidExtension = extAware.extensions.create<KiteConfigAndroidExtension>("android")
        val iosExtension = extAware.extensions.create<KiteConfigIosExtension>("ios")
        (iosExtension as ExtensionAware).extensions.create<KiteInfoPlistScope>("infoPlist")
        iosExtension.xcodeTargetNames.convention(emptyList())
        extAware.extensions.create<KiteLogoScope>("logo")
        extAware.extensions.create<KiteConfigNativeOptInsExtension>("optIns").apply {
            builtIns.convention(true)
            markers.convention(emptyList())
            projects.convention(emptyList())
        }
        val webExtension = extAware.extensions.create<KiteConfigWebExtension>("web")
        (webExtension as ExtensionAware).extensions.create<KiteConfigIoWorkerExtension>("ioWorker").apply {
            targets.convention(emptyList())
            projects.convention(emptyList())
        }
        extAware.extensions.create<KiteConfigBuildConfigExtension>("buildConfig").apply {
            packageName.convention(KiteConfigExtension.DEFAULT_GENERATED_PACKAGE)
            className.convention("KiteBuildConfig")
            includeIdentity.convention(true)
            allowBuildCache.convention(false)
            fields.convention(emptyList())
        }
        val desktopExtension = extAware.extensions.create<KiteConfigDesktopExtension>("desktop")

        // Every platform block carries the same identity details, so the shared
        // facts and their per-platform differences read the same way everywhere.
        listOf(androidExtension, iosExtension, desktopExtension).forEach { platform ->
            (platform as ExtensionAware).extensions.create<KiteAppNameScope>("appNameDetails")
            platform.extensions.create<KiteAppIdScope>("appIdDetails")
            platform.extensions.create<KitePlatformVersionScope>("versionDetails")
            if (platform is KiteConfigAndroidExtension) {
                platform.extensions.create<KiteAndroidLocalesScope>("localesDetails")
            } else {
                platform.extensions.create<KiteLocalesScope>("localesDetails")
            }
            // A convention rather than orElse: a ListProperty is always present, so
            // this is what lets an explicitly empty list mean "no locales here"
            // instead of silently inheriting the shared one.
            platform.localesDetails.tags.convention(ext.effectiveLocales)
            if (platform !is KiteConfigIosExtension) {
                platform.extensions.create<KiteJvmScope>("jvmDetails")
            }
            if (platform is KiteConfigDesktopExtension) {
                platform.extensions.create<KiteDesktopLogoScope>("logoDetails")
            } else {
                platform.extensions.create<KiteLogoScope>("logoDetails")
            }
            if (platform is KiteConfigAndroidExtension) {
                platform.extensions.create<KiteAndroidSplashScope>("splashDetails")
            } else {
                platform.extensions.create<KiteSplashScope>("splashDetails")
            }
        }

        // The debug topic: one root block, plus a corner on each platform that has a debug build.
        val rootDebug = extAware.extensions.create<KiteDebugScope>("debug")
        val androidDebug = (androidExtension as ExtensionAware).extensions.create<KiteAndroidDebugScope>("debugDetails")
        androidDebug.buildTypes.convention(listOf("debug"))
        val iosDebug = (iosExtension as ExtensionAware).extensions.create<KiteIosDebugScope>("debugDetails")
        iosDebug.configurations.convention(listOf("Debug"))
        listOf(rootDebug, androidDebug, iosDebug).forEach { scope ->
            (scope as ExtensionAware).extensions.create<KiteAppNameScope>("appNameDetails")
            scope.extensions.create<KiteAppIdScope>("appIdDetails")
            scope.extensions.create<KiteLogoScope>("logoDetails")
        }
        // The debug catalog is the release catalog's sibling, named after it.
        ext.iosDebugAppIconDirectory.set(
            target.layout.dir(
                ext.effectiveIosAppIconDirectory.map { catalog ->
                    val release = catalog.asFile
                    release.parentFile.resolve(release.name.removeSuffix(".appiconset") + "-Debug.appiconset")
                },
            ),
        )

        // Resolve console colour once. Tasks receive only the Boolean, which keeps
        // them configuration-cache safe and stops every task action re-detecting.
        val colorSupported = target.provider {
            resolveColorSupport(
                explicit = target.providers.gradleProperty("kiteconfig.color").orNull
                    ?.let { strictBooleanProperty("kiteconfig.color", it) },
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
        // so a lazy parse would accept "-Pkiteconfig.backups=treu" on one invocation
        // and silently drop backups on the next. A protection switch has to reject
        // a value it does not understand the moment it is supplied.
        target.providers.gradleProperty("kiteconfig.dryRun").orNull
            ?.let { ext.dryRunOverride.set(strictBooleanProperty("kiteconfig.dryRun", it)) }
        target.providers.gradleProperty("kiteconfig.backups").orNull
            ?.let { ext.backupsOverride.set(strictBooleanProperty("kiteconfig.backups", it)) }

        ext.ios.apply {

            podfile.convention(target.layout.projectDirectory.file("iosApp/Podfile"))

            appDirectory.convention(target.layout.projectDirectory.dir("iosApp"))
            appIconDirectory.convention(
                target.layout.projectDirectory.dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"),
            )
        }
        // Auto-detect locales from the selected Compose resources directory's values-* children.
        ext.localesScope.tags.convention(target.provider { autoDetectLocales(target, ext) })

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
        registerSyncIosDebugLogoTask(target, ext)
        registerSyncAndroidLogoTask(target, ext, resolvedAndroidAppDirectory, androidOutputApproved)
        registerCleanupLegacyLogoTask(target, ext, resolvedAndroidAppDirectory, androidOutputApproved)
        // One verb. kiteApply applies every selected platform's plan; the
        // per-platform tasks apply one, which is what an ordinary Android build
        // depends on so it can never drag iOS source edits along with it.
        target.tasks.register("kiteApplyAndroid") {
            group = "kiteconfig"
            description = "Applies the Android source changes: launcher icons and their ownership."
            dependsOn("kiteInternalAndroidLogo", "kiteInternalLegacyIconCleanup")
        }
        target.tasks.register("kiteApplyIos") {
            group = "kiteconfig"
            description = "Applies the iOS source changes: Xcode settings, plist, catalog, launch screen."
            dependsOn("kiteInternalIosConfig", "kiteInternalPlistClean", "kiteInternalIosLogo", "kiteInternalIosDebugLogo")
        }
        target.tasks.register("kiteApply") {
            group = "kiteconfig"
            description = "Applies the configured source changes for every selected platform."
            dependsOn("kiteApplyAndroid", "kiteApplyIos")
        }
        wireAutomaticApplication(target, ext)
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
            // Rethrown as it stands: the cause chain carries which provider failed,
            // and a build that loses it says only that a property could not be read.
            collectModelFailures(ext).firstOrNull()?.let { throw it }
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
                DesktopWiring.wireDesktopSplash(consumerProject, ext, isResilientDiagnosticInvocation(target))
            }
            plugins.withId("org.jetbrains.compose") {
                detectedComposeProjects += consumerProject.path
            }
            // The Android wiring runs on the diagnostic invocations too, unlike the KGP
            // wiring below: AGP validates its DSL on every invocation, so a consumer whose
            // compileSdk lives only in kiteConfig { } would fail configuration before the
            // diagnostic task could report anything. The adapters guard each value group
            // themselves on resilient runs and skip a failing provider instead of aborting.
            plugins.withId("com.android.application") {
                detectedAndroidApplications += consumerProject.path
                detectedAndroidProjects += consumerProject.path
                val resilient = isResilientDiagnosticInvocation(target)
                if (typedIntegrationsActive(agpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
                    if (useAgp8ClassicAdapter) {
                        Agp8ClassicAndroidWiringBridge.wireApplication(consumerProject, ext, resilient)
                    } else {
                        ClassicAndroidWiring.wireApplication(consumerProject, ext, resilient)
                    }
                    wireAndroidSplash(consumerProject, ext, resilient)
                    wireAndroidManifestOverlay(consumerProject, ext, resilient)
                    wireAndroidDebugIcons(consumerProject, ext, resilient)
                } else if (!AGP_ON_CLASSPATH) {
                    androidProjectsWithoutSharedClassloader += consumerProject.path
                }
            }
            plugins.withId("com.android.library") {
                detectedAndroidProjects += consumerProject.path
                val resilient = isResilientDiagnosticInvocation(target)
                if (typedIntegrationsActive(agpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
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
                if (typedIntegrationsActive(agpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
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
                // classloader kiteconfig can't see, and merely CALLING a method whose
                // body references KGP types throws NoClassDefFoundError. Guard here
                // (outside any KGP-typed method) and degrade with guidance.
                if (isResilientDiagnosticInvocation(target)) {
                    // Diagnostic/report tasks resolve provider failures themselves;
                    // do not let peer-plugin adapters pre-empt those reports.
                } else if (typedIntegrationsActive(kgpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
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
                        ext.effectiveJvmToolchain.orNull?.let { toolchain ->
                            KotlinJvmTargetWiring.applyToolchain(consumerProject, toolchain)
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
                    } else if (typedIntegrationsActive(kgpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
                        consumerProject.afterEvaluate {
                            ext.effectiveJvmTarget.orNull?.let { javaVersion ->
                                val targetVersion = org.gradle.api.JavaVersion
                                    .toVersion(validateJavaVersion(javaVersion))
                                    .toString()
                                KotlinJvmTargetWiring.apply(consumerProject, targetVersion)
                            }
                            ext.effectiveJvmToolchain.orNull?.let { toolchain ->
                                KotlinJvmTargetWiring.applyToolchain(consumerProject, toolchain)
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
                diagnosticBoolean { ext.appNameFlowsAnywhere().get() && ext.effectiveAppName.isPresent } ||
                    diagnosticBoolean { ext.appIdFlowsAnywhere().get() && ext.appId.isPresent } ||
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
            // Read-only reflection, safe to run ahead of the resilient-diagnostic early
            // return below: doctor/check need the desktop-app census regardless of
            // whether this invocation is resilient. Computed once here and reused by
            // every later census read in this block, guarded the same way every time:
            // a duplicated, unguarded copy of this filter is what caused the
            // NoClassDefFoundError crash this comment used to sit next to.
            val detectedDesktopApplications = if (COMPOSE_ON_CLASSPATH) {
                detectedComposeProjects.filter { path -> target.findProject(path)?.let(DesktopWiring::isDesktopApp) == true }
            } else {
                emptyList()
            }
            val diagnosticNeedsComposeIntegration = detectedComposeProjects.isNotEmpty() &&
                diagnosticBoolean { ext.effectiveDesktopEnabled.get() }
            listOf(doctorTask, checkTask).forEach { diagnosticTask ->
                diagnosticTask.configure {
                    detectedAndroidApplicationProjects.set(detectedAndroidApplications.toList())
                    androidManifestPaths.set(
                        manifestDirectories.map { it.resolve("src/main/AndroidManifest.xml").path },
                    )
                    androidResPaths.set(resourceDirectories.map { it.resolve("src/main/res").path })
                    agpRequired.set(diagnosticNeedsAndroidIntegration)
                    kgpRequired.set(diagnosticNeedsKgpIntegration)
                    composeRequired.set(diagnosticNeedsComposeIntegration)
                    detectedDesktopApplicationProjects.set(detectedDesktopApplications)
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
            // Locales resolve from the shared module, so they only become knowable
            // once the census above has run. Lock the list here rather than on
            // first read: a build script that reads it earlier would otherwise
            // freeze an empty list for the whole build.
            //
            // This sits below the resilient bail-out on purpose. Finalizing forces
            // the detection convention, which can throw, and kiteDoctor exists to
            // report a broken model rather than die inside configuration.
            ext.localesScope.tags.finalizeValue()
            if (detectedAndroidProjects.isNotEmpty() && ext.effectiveApplySdkLevels.get()) {
                validateSdkLevels(
                    ext.android.compileSdk.orNull,
                    ext.android.minSdk.orNull,
                    ext.android.targetSdk.orNull,
                )
                ext.android.ndk.orNull?.let(::validateNdkVersion)
            }
            if (detectedAndroidApplications.isNotEmpty() && ext.effectivePropagateVersion.get()) {
                ext.android.versionDetails.publishedBuildNumber.orNull?.let { published ->
                    validatePublishedVersionCode(ext.effectiveAndroidVersionCode.orNull, published)
                }
            }
            if (ext.effectiveSyncIos.get() && ext.effectivePropagateVersion.get()) {
                ext.ios.versionDetails.publishedBuildNumber.orNull?.let { published ->
                    validatePublishedBuildNumber(ext.effectiveIosBuildNumber.orNull, published)
                }
            }
            if (detectedDesktopApplications.isNotEmpty() && ext.effectivePropagateVersion.get()) {
                ext.desktop.versionDetails.publishedBuildNumber.orNull?.let { published ->
                    validatePublishedBuildNumber(ext.effectiveDesktopBuildNumber.orNull, published, platform = "desktop")
                }
            }
            val needsApplicationSelection =
                (ext.appNameFlowsAnywhere().get() && ext.effectiveAppName.isPresent) ||
                    (ext.appIdFlowsAnywhere().get() && ext.appId.isPresent) ||
                    (ext.effectivePropagateVersion.get() &&
                        (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)) ||
                    ext.effectiveFilterAndroidResources.get()
            val usesApplicationSelection = needsApplicationSelection || ext.effectiveAndroidLogoInstall.get()
            val selectedApplications = if (usesApplicationSelection) {
                ext.effectiveAndroidApps.get()
            } else {
                emptyList()
            }
            when {
                selectedApplications.isEmpty() && detectedAndroidApplications.size > 1 && needsApplicationSelection ->
                    throw GradleException(
                        "kiteConfig found multiple Android application projects while app-scoped values are enabled: " +
                            "${detectedAndroidApplications.joinToString()}. Select the intended targets " +
                            "explicitly with modules { androidApps(\":app\")."
                    )

                selectedApplications.isNotEmpty() -> {
                    val unknown = selectedApplications.toSet() - detectedAndroidApplications
                    if (unknown.isNotEmpty()) {
                        throw GradleException(
                            "kiteConfig { modules { androidApps } } contains project paths that do not " +
                                "apply com.android.application: ${unknown.sorted().joinToString()}."
                        )
                    }
                    if (selectedApplications.size > 1 && ext.appIdFlowsAnywhere().get() && ext.appId.isPresent) {
                        throw GradleException(
                            "kiteConfig refuses to assign one Android applicationId to multiple selected apps " +
                                "(${selectedApplications.joinToString()}). Select one app, or set " +
                                "android { appId { enabled = false } } and give each app module its own identifier."
                        )
                    }
                }
            }

            val effectiveApplications = selectedApplications.ifEmpty { detectedAndroidApplications.toList() }
            if (ext.effectiveAndroidLogoInstall.get() &&
                effectiveApplications.size > 1
            ) {
                throw GradleException(
                    "kiteConfig Android logo installation has one output sink but resolved multiple " +
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
                    "kiteConfig cannot configure requested KMP integrations in " +
                        "${kmpProjectsWithoutSharedClassloader.joinToString()}: Kotlin Gradle plugin " +
                        "classes are isolated in a sibling classloader. Declare " +
                        "kotlin(\"multiplatform\") (apply false) in the root plugins block so KGP and " +
                        "kiteconfig share a classloader. No requested integration was silently skipped."
                    )
            }

            val needsAndroidIntegration = detectedAndroidProjects.isNotEmpty() && (
                (ext.appNameFlowsAnywhere().get() && ext.effectiveAppName.isPresent) ||
                    (ext.appIdFlowsAnywhere().get() && ext.appId.isPresent) ||
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
                    "kiteConfig cannot configure requested Android integrations in " +
                        "${androidProjectsWithoutSharedClassloader.joinToString()}: AGP classes are " +
                        "isolated in a sibling classloader. Declare the Android plugin versions with " +
                        "apply false in the root plugins block. No requested Android value was silently skipped."
                )
            }
            if (ext.effectiveIgnoreVersionGuards.get()) {
                val offMatrix = buildList {
                    activeAgpVersion?.takeIf { !isSupportedAgpVersion(it) }?.let { add("AGP $it") }
                    activeKgpVersion?.takeIf { !isSupportedKgpVersion(it) }?.let { add("KGP $it") }
                }
                if (offMatrix.isNotEmpty()) {
                    target.logger.warn(
                        "[kiteConfig] ignoreVersionGuards is on: " + offMatrix.joinToString() +
                            " is outside the tested range. Typed integrations stay active; " +
                            "breakage on these versions is expected to be yours to keep.",
                    )
                }
            }
            if (needsAndroidIntegration && AGP_ON_CLASSPATH) {
                val agpVersion = activeAgpVersion
                    ?: throw GradleException(
                        "[KITECONFIG-COMPAT-001] Could not determine the active Android Gradle plugin version. " +
                            "Supported AGP range is 8.5.2 through 9.3.x."
                    )
                if (!isSupportedAgpVersion(agpVersion) && !ext.effectiveIgnoreVersionGuards.get()) {
                    throw GradleException(
                        "[KITECONFIG-COMPAT-002] Unsupported Android Gradle plugin $agpVersion; " +
                            "this kiteconfig build supports AGP 8.5.2 through 9.3.x. " +
                            "Set kiteConfig { ignoreVersionGuards = true } to proceed anyway."
                    )
                }
            }
            val needsKmpAndroidSdk = ext.effectiveApplySdkLevels.get() &&
                (ext.android.compileSdk.isPresent || ext.android.minSdk.isPresent)
            if (needsKmpAndroidSdk && kmpAndroidProjectsWithoutComponents.isNotEmpty()) {
                throw GradleException(
                    "[KITECONFIG-COMPAT-005] The KMP Android components extension was unavailable in " +
                        kmpAndroidProjectsWithoutComponents.joinToString() +
                        "; compileSdk/minSdk were not silently skipped. Verify the supported AGP/KGP plugin shape."
                )
            }
            if (needsKgpIntegration && KGP_ON_CLASSPATH) {
                val kgpVersion = activeKgpVersion
                    ?: throw GradleException(
                        "[KITECONFIG-COMPAT-003] Could not determine the active Kotlin Gradle plugin version. " +
                            "This kiteconfig build supports KGP 2.4.x."
                    )
                if (!isSupportedKgpVersion(kgpVersion) && !ext.effectiveIgnoreVersionGuards.get()) {
                    throw GradleException(
                        "[KITECONFIG-COMPAT-004] Unsupported Kotlin Gradle plugin $kgpVersion; " +
                            "this kiteconfig build supports KGP 2.4.x. " +
                            "Set kiteConfig { ignoreVersionGuards = true } to proceed anyway."
                    )
                }
            }

            // Compose applied directly to root breaks the root-before-subproject ordering
            // guarantee every other desktop write below depends on.
            if (ext.effectiveDesktopEnabled.get() &&
                target.plugins.hasPlugin("org.jetbrains.compose") &&
                DesktopWiring.isDesktopApp(target)
            ) {
                throw GradleException(
                    "kiteConfig cannot propagate desktop identity when org.jetbrains.compose is applied to the root " +
                        "project and the root project is itself the desktop app. Ordering against Compose is only " +
                        "guaranteed for subprojects. Declare Compose at the root with `apply false` and move the " +
                        "application into its own module.",
                )
            }

            // Desktop selection needs the whole project census, so it lands here rather
            // than in the per-project afterEvaluate that writes the identity values.
            if (ext.effectiveDesktopEnabled.get() && detectedComposeProjects.isNotEmpty()) {
                if (!COMPOSE_ON_CLASSPATH || !KGP_ON_CLASSPATH) {
                    throw GradleException(
                        "[KITECONFIG-COMPAT-007] kiteConfig cannot configure requested desktop values in " +
                            "${detectedComposeProjects.joinToString()}: Compose Gradle plugin classes are " +
                            "isolated in a sibling classloader. Declare org.jetbrains.compose and " +
                            "kotlin(\"multiplatform\") with apply false in the root plugins block so Compose " +
                            "and kiteconfig share a classloader. No requested desktop value was silently skipped."
                    )
                }
                val selectedDesktopApps = ext.effectiveDesktopApps.get()
                selectedDesktopApps.forEach { validateGradleProjectPath(it, "modules { desktopApps }") }
                if (selectedDesktopApps.distinct().size != selectedDesktopApps.size) {
                    throw GradleException("kiteConfig { modules { desktopApps } } contains duplicate project paths.")
                }
                if (selectedDesktopApps.isEmpty()) {
                    if (detectedDesktopApplications.size > 1) {
                        throw GradleException(
                            "kiteConfig found multiple Compose Desktop application projects while desktop " +
                                "values are enabled: ${detectedDesktopApplications.joinToString()}. Select the " +
                                "intended targets explicitly with modules { desktopApps(\":desktopApp\") }."
                        )
                    }
                } else {
                    val unknown = selectedDesktopApps.toSet() - detectedComposeProjects
                    if (unknown.isNotEmpty()) {
                        throw GradleException(
                            "kiteConfig { modules { desktopApps } } contains project paths that do not apply " +
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
                        0 -> "kiteConfig buildConfig is enabled but no shared project is selected, and no " +
                            "project applying org.jetbrains.kotlin.multiplatform was detected. Set " +
                            "modules { shared = \":shared\" }."
                        else -> "kiteConfig buildConfig is enabled but no shared project is selected, and " +
                            "${detectedKmpProjects.size} projects apply org.jetbrains.kotlin.multiplatform: " +
                            "${detectedKmpProjects.sorted().joinToString()}. Pick one with modules { shared }."
                    }
                )
            }
            if (ext.effectiveBuildConfigEnabled.get() && selectedSharedProject !in detectedKmpProjects) {
                throw GradleException(
                    "kiteConfig buildConfig project '\$selectedSharedProject' does not apply " +
                        "org.jetbrains.kotlin.multiplatform."
                )
            }
            if (ext.effectiveFilterAndroidResources.get() && ext.canonicalLocales.get().isEmpty()) {
                throw GradleException(
                    "kiteConfig { android { locales { filterResources } } } requires at least one locale. " +
                        "Configure locales explicitly or add a supported values-<locale> resource directory."
                )
            }
            // Shared-scoped generation, wired once the shared module is settled. Each
            // function still self-selects (web can name projects beyond the shared one),
            // so simply offer every detected KMP project.
            if (typedIntegrationsActive(kgpAdaptersUsable, ext.effectiveIgnoreVersionGuards.get())) {
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
                        "kiteConfig interop opt-ins need an explicit KMP scope. Set " +
                            "optIns { projects(\":shared\") } or modules { shared = \":shared\" }."
                    )
                }
                val invalid = interopProjects.toSet() - detectedKmpProjects
                if (invalid.isNotEmpty()) {
                    throw GradleException(
                        "kiteConfig interop project selector(s) do not apply Kotlin Multiplatform: " +
                            invalid.sorted().joinToString()
                    )
                }
            }
            if (ext.effectiveIoWorkerEnabled.get()) {
                val webProjects = ext.effectiveIoWorkerProjects.get()
                webProjects.forEach { validateGradleProjectPath(it, "web.projectPaths") }
                if (webProjects.distinct().size != webProjects.size) {
                    throw GradleException("kiteConfig { web { projectPaths } } contains duplicate project paths.")
                }
                val effectiveWebProjects = webProjects.ifEmpty {
                    listOfNotNull(selectedSharedProject)
                }
                if (effectiveWebProjects.isEmpty()) {
                    throw GradleException(
                        "kiteConfig web worker generation needs an explicit KMP project. Set " +
                            "web { ioWorker { projects(\":shared\") } } or modules { shared = \":shared\" }."
                    )
                }
                val invalid = effectiveWebProjects.toSet() - detectedKmpProjects
                if (invalid.isNotEmpty()) {
                    throw GradleException(
                        "kiteConfig web project selector(s) do not apply Kotlin Multiplatform: " +
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
    private fun propagateInteropOptIns(project: Project, ext: KiteConfigExtension) {
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
     * `kiteConfig { web { generateIoWorker = true } }`. wasmJs and non-JS selectors
     * are rejected.
     */
    private fun wireWebIoWorker(project: Project, ext: KiteConfigExtension) {
        if (!ext.effectiveIoWorkerEnabled.get()) return
        val selectedProjects = ext.effectiveIoWorkerProjects.get().ifEmpty {
            listOfNotNull(ext.effectiveSharedProjectPath.orNull)
        }
        if (project.path !in selectedProjects) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val requestedNames = ext.effectiveIoWorkerTargets.get()
        if (requestedNames.isEmpty()) {
            throw GradleException(
                "kiteConfig web worker generation is enabled for ${project.path}, but no browser target " +
                    "was selected. Add web { ioWorker { targets(\"js\") } } with the exact Kotlin/JS " +
                    "browser target name. Browser capability is never inferred."
            )
        }
        if (requestedNames.any { it.isBlank() } || requestedNames.distinct().size != requestedNames.size) {
            throw GradleException(
                "kiteConfig { web { ioWorker { targets } } } must contain unique, non-blank target names."
            )
        }
        val allTargetsByName = kmp.targets.associateBy { it.targetName }
        val invalidTargets = requestedNames.filter { allTargetsByName[it]?.platformType != KotlinPlatformType.js }
        if (invalidTargets.isNotEmpty()) {
            throw GradleException(
                "kiteConfig { web { ioWorker { targets } } } named ${invalidTargets.joinToString()} in " +
                    "${project.path}, which are not Kotlin/JS targets. wasmJs is unsupported."
            )
        }
        val jsTargets = requestedNames.map { allTargetsByName.getValue(it) }

        // The helper needs Worker, Blob, and object URLs, none of which exist in
        // Node. Naming a Node-only target used to generate code that compiled and
        // then failed at runtime, which is the worst place to find out.
        // Only inline operations here, and only String afterwards. A lambda over a
        // KGP type becomes a synthetic method whose signature names that type, and
        // Gradle then cannot decorate this plugin at all when KGP is absent.
        val nodeOnly = jsTargets
            .filter { (it as? KotlinJsSubTargetContainerDsl)?.isBrowserConfigured == false }
            .map { it.targetName }
        if (nodeOnly.isNotEmpty()) {
            throw GradleException(
                "kiteConfig { web { ioWorker { targets } } } named ${nodeOnly.joinToString()} in " +
                    "${project.path}, which declare nodejs() and not browser(). The generated helper " +
                    "needs the browser Worker and Blob APIs. Add browser() to that target, or name a " +
                    "target that has it."
            )
        }

        // Validate the destination package early. A malformed value would otherwise
        // appear as a confusing compile error inside the generated file.
        val pkg = ext.effectiveIoWorkerPackage.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kiteConfig { web { ioWorkerPackage } } \"$pkg\" $it")
        }

        // Derive the source set + compile task from each js target's ACTUAL name,
        // so a custom-named target (`js("web")` → webMain / compileKotlinWeb) is
        // wired too, instead of silently no-op'ing on a hardcoded `js()` name.
        jsTargets.forEach { target ->
            val name = target.targetName
            val capital = name.replaceFirstChar { it.uppercase() }
            val genDir = project.layout.buildDirectory.dir("generated/kiteconfig/${name}Main/kotlin")
            val genTask = project.tasks.register<GenerateIoWorkerTask>("kiteInternalIoWorker$capital") {
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
    private fun wireBuildConfig(project: Project, ext: KiteConfigExtension) {
        val cfg = ext.buildConfig
        if (!ext.effectiveBuildConfigEnabled.get()) return
        if (!ext.effectiveSharedProjectPath.isPresent || project.path != ext.effectiveSharedProjectPath.get()) return
        val kmp = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return

        val pkg = cfg.packageName.get()
        invalidWorkerPackageReason(pkg)?.let {
            throw GradleException("kiteConfig { buildConfig { packageName } } \"$pkg\" $it")
        }
        val cls = cfg.className.get()
        if (!isValidKotlinIdentifier(cls)) {
            throw GradleException(
                "kiteConfig { buildConfig { className } } \"$cls\" is not a valid Kotlin identifier."
            )
        }

        val genDir = project.layout.buildDirectory.dir("generated/kiteconfig/commonMain/kotlin")
        val genTask = project.tasks.register<GenerateBuildConfigTask>("kiteInternalBuildConfig") {
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

    private fun autoDetectLocales(root: Project, ext: KiteConfigExtension): List<String> {
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
        ext: KiteConfigExtension,
    ): TaskProvider<SanitizeIosProjectTask> =
        root.tasks.register<SanitizeIosProjectTask>("kiteInternalPlistClean") {
            val runCondition = KiteOperationPlanner.enabled(ext, KiteOperation.IOS_PLIST)
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            infoPlistFile.set(ext.effectiveIosInfoPlist)
            propagateAppName.set(ext.effectiveAppNameFor(KitePlatform.IOS).map { ext.appNameFlowsTo(KitePlatform.IOS).get() }.orElse(false))
            propagateMarketingVersion.set(
                ext.effectiveIosMarketingVersion.map { ext.versionFlowsTo(KitePlatform.IOS).get() }.orElse(false)
            )
            propagateBuildNumber.set(
                ext.effectiveIosBuildNumber.map { ext.versionFlowsTo(KitePlatform.IOS).get() }.orElse(false)
            )
            usesNonExemptEncryption.set(ext.effectiveNonExemptEncryption)
            proMotion120Hz.set(ext.effectiveProMotion)
            locales.set(ext.canonicalLocales)
            propagateLocaleList.set(
                ext.canonicalLocales.map { it.isNotEmpty() && ext.localesFlowsTo(KitePlatform.IOS).get() },
            )
            conflictPolicy.set(ext.effectivePlistConflictPolicy)
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncIosTask(
        root: Project,
        ext: KiteConfigExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("kiteInternalIosConfig") {
            val runCondition = KiteOperationPlanner.enabled(ext, KiteOperation.IOS_PROJECT)
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            pbxprojFile.set(ext.effectiveIosPbxproj)
            infoPlistFile.set(ext.effectiveIosInfoPlist)
            infoPlistExplicit.set(ext.effectiveIosInfoPlistExplicit)
            pbxprojExplicit.set(ext.effectiveIosPbxprojExplicit)
            podfile.set(ext.effectiveIosPodfile)
            iosAppDir.set(ext.effectiveIosAppDirectory)
            appiconsetDir.set(ext.effectiveIosAppIconDirectory)
            marketingVersion.set(ext.effectiveIosMarketingVersion)
            buildNumber.set(ext.effectiveIosBuildNumber)
            appName.set(ext.effectiveAppNameFor(KitePlatform.IOS))
            bundleId.set(ext.iosBundleId)
            locales.set(ext.canonicalLocales)
            targetNames.set(ext.effectiveIosTargets)
            iosSharedModuleName.set(ext.effectiveIosSharedModuleName)
            iosPreviousSharedModuleName.set(ext.effectiveIosPreviousSharedModuleName)
            debugConfigurationNames.set(ext.effectiveIosDebugConfigurations)
            debugBundleId.set(
                ext.effectiveDebugAppIdFor(KitePlatform.IOS).map { id -> id.takeIf { ext.debugAppIdFlowsTo(KitePlatform.IOS).get() } },
            )
            debugAppName.set(
                ext.effectiveDebugAppNameFor(KitePlatform.IOS).map { name -> name.takeIf { ext.debugAppNameFlowsTo(KitePlatform.IOS).get() } },
            )
            debugAppIconName.set(
                ext.iosDebugAppIconDirectory.map { dir ->
                    if (ext.debugLogoFlowsTo(KitePlatform.IOS).get()) iosAppIconCatalogName(dir.asFile.name) else null
                },
            )
            propagateVersion.set(ext.versionFlowsTo(KitePlatform.IOS))
            propagateAppName.set(ext.effectiveAppNameFor(KitePlatform.IOS).map { ext.appNameFlowsTo(KitePlatform.IOS).get() }.orElse(false))
            propagateBundleId.set(ext.appId.map { ext.appIdFlowsTo(KitePlatform.IOS).get() }.orElse(false))
            propagateLocaleList.set(
                ext.canonicalLocales.map { it.isNotEmpty() && ext.localesFlowsTo(KitePlatform.IOS).get() }
            )
            propagateSharedModule.set(ext.effectivePropagateSharedModule)
            propagateLogo.set(ext.effectiveIosLogoInstall)
            splashEnabled.set(ext.effectiveIosSplash)
            splashColor.set(ext.effectiveSplashColor)
            splashDarkColor.set(ext.effectiveSplashDarkColor)
            splashImage.set(ext.effectiveSplashImage)
            splashDarkImage.set(ext.effectiveSplashDarkImage)
            sanitizeSourcePlist.set(ext.effectiveSanitizeIosProject)
            usesNonExemptEncryption.set(ext.effectiveNonExemptEncryption)
            proMotion120Hz.set(ext.effectiveProMotion)
            plistConflictPolicy.set(ext.effectivePlistConflictPolicy)
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncIosLogoTask(
        root: Project,
        ext: KiteConfigExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("kiteInternalIosLogo") {
            val runCondition = KiteOperationPlanner.enabled(ext, KiteOperation.IOS_APP_ICON)
            onlyIf { runCondition.get() }
            foregroundPng.set(ext.effectiveLogoForegroundFor(KitePlatform.IOS))
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.fileProvider(ext.effectiveLogoBackgroundImageFor(KitePlatform.IOS))
            backgroundColorHex.set(ext.effectiveLogoBackgroundColorFor(KitePlatform.IOS))
            foregroundScale.set(ext.effectiveLogoScaleFor(KitePlatform.IOS))
            overlayPng.set(ext.effectiveLogoOverlayFor(KitePlatform.IOS))
            appiconsetDir.set(ext.effectiveIosAppIconDirectory)
            outputFiles.from(ext.effectiveIosAppIconDirectory.map { dir -> SyncIosLogoTask.OUTPUT_FILE_NAMES.map { dir.file(it) } })
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    /**
     * The debug catalog installer: the release task class pointed at the sibling
     * catalog, with an install switch that takes the catalog back out when off.
     */
    private fun registerSyncIosDebugLogoTask(
        root: Project,
        ext: KiteConfigExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("kiteInternalIosDebugLogo") {
            val installCondition = KiteOperationPlanner.enabled(ext, KiteOperation.IOS_DEBUG_APP_ICON)
            val removalCondition = KiteOperationPlanner.enabled(ext, KiteOperation.IOS_DEBUG_APP_ICON_REMOVAL)
            onlyIf { installCondition.get() || removalCondition.get() }
            description = "Composite the debug icon into the iOS AppIcon-Debug.appiconset, or remove it when off."
            debugVariant.set(true)
            install.set(installCondition)
            foregroundPng.set(ext.effectiveDebugLogoForegroundFor(KitePlatform.IOS))
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.fileProvider(ext.effectiveDebugLogoBackgroundImageFor(KitePlatform.IOS))
            backgroundColorHex.set(ext.effectiveDebugLogoBackgroundColorFor(KitePlatform.IOS))
            foregroundScale.set(ext.effectiveDebugLogoScaleFor(KitePlatform.IOS))
            overlayPng.set(ext.effectiveDebugLogoOverlayFor(KitePlatform.IOS))
            appiconsetDir.set(ext.iosDebugAppIconDirectory)
            outputFiles.from(ext.iosDebugAppIconDirectory.map { dir -> SyncIosLogoTask.OUTPUT_FILE_NAMES.map { dir.file(it) } })
            dryRun.set(ext.effectiveDryRun)
            backup.set(ext.effectiveBackups)
        }

    private fun registerSyncAndroidLogoTask(
        root: Project,
        ext: KiteConfigExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        androidOutputApproved: Provider<Boolean>,
    ): TaskProvider<SyncAndroidLogoTask> =
        root.tasks.register<SyncAndroidLogoTask>("kiteInternalAndroidLogo") {
            val runCondition = KiteOperationPlanner.enabled(ext, KiteOperation.ANDROID_LAUNCHER_ICON)
            onlyIf { runCondition.get() }
            foregroundPng.set(ext.effectiveLogoForegroundFor(KitePlatform.ANDROID))
            projectRootDir.set(root.layout.projectDirectory)
            backgroundPng.fileProvider(ext.effectiveLogoBackgroundImageFor(KitePlatform.ANDROID))
            backgroundColorHex.set(ext.effectiveLogoBackgroundColorFor(KitePlatform.ANDROID))
            safeZoneRatio.set(ext.effectiveLogoSafeZone)
            overlayPng.set(ext.effectiveLogoOverlayFor(KitePlatform.ANDROID))
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
        ext: KiteConfigExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        androidOutputApproved: Provider<Boolean>,
    ): TaskProvider<CleanupLegacyAppLogoArtifactsTask> =
        root.tasks.register<CleanupLegacyAppLogoArtifactsTask>("kiteInternalLegacyIconCleanup") {
            val runCondition = KiteOperationPlanner.enabled(ext, KiteOperation.ANDROID_LEGACY_ICONS)
            onlyIf { runCondition.get() }
            projectRootDir.set(root.layout.projectDirectory)
            dryRun.set(ext.effectiveDryRun)
            outputSinkApproved.set(androidOutputApproved)
            androidResDir.set(resolvedAndroidAppDirectory.dir("src/main/res"))
        }

    private fun registerVerifyTask(
        root: Project,
        ext: KiteConfigExtension,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteConfigVerifyTask> =
        root.tasks.register<KiteConfigVerifyTask>("kiteVerify") {
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
            // Mirrors the KTCNFG087 PASS gate exactly, so verify and doctor never show
            // conflicting states for the same inputs: desktop enabled, derivation
            // opted in, bundle-id propagation on, and an appId to derive from.
            desktopUpgradeCode.set(root.resilientValue {
                if (ext.effectiveDesktopEnabled.get() &&
                    ext.desktop.deriveUpgradeUuid.getOrElse(false) &&
                    ext.appIdFlowsTo(KitePlatform.DESKTOP).get() &&
                    ext.appId.isPresent
                ) {
                    deriveUpgradeUuid(ext.appId.get())
                } else {
                    null
                }
            })
            // Mirrors the KTCNFG086 gate: an explicit override always wins, otherwise
            // the derived slug, so a release engineer sees exactly what will apply.
            desktopLinuxPackageName.set(root.resilientValue {
                if (ext.effectiveDesktopEnabled.get() &&
                    ext.appNameFlowsTo(KitePlatform.DESKTOP).get() &&
                    ext.effectiveAppNameFor(KitePlatform.DESKTOP).isPresent
                ) {
                    ext.desktop.linuxPackageName.orNull ?: deriveLinuxPackageName(ext.effectiveAppNameFor(KitePlatform.DESKTOP).get())
                } else {
                    null
                }
            })
            desktopIcons.set(ext.effectiveDesktopIcons)
            debugAndroidAppName.set(ext.debugAppNameOverrideFor(KitePlatform.ANDROID))
            debugIosAppName.set(ext.debugAppNameOverrideFor(KitePlatform.IOS))
            debugAndroidApplicationId.set(root.resilientValue { ext.debugAppIdOverrideFor(KitePlatform.ANDROID).orNull })
            debugIosBundleId.set(root.resilientValue { ext.debugAppIdOverrideFor(KitePlatform.IOS).orNull })
            debugAndroidIcon.set(root.resilientValue { ext.debugLogoFlowsTo(KitePlatform.ANDROID).orNull })
            debugIosIcon.set(root.resilientValue { ext.debugLogoFlowsTo(KitePlatform.IOS).orNull })
        }

    private fun registerDoctorTask(
        root: Project,
        ext: KiteConfigExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteConfigDoctorTask> =
        root.tasks.register<KiteConfigDoctorTask>("kiteDoctor") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory, colorSupported)
        }

    private fun registerCheckTask(
        root: Project,
        ext: KiteConfigExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteConfigCheckTask> =
        root.tasks.register<KiteConfigCheckTask>("kiteCheck") {
            bindDiagnosticInputs(root, ext, resolvedAndroidAppDirectory, colorSupported)
        }

    private fun registerPlanTask(
        root: Project,
        colorSupported: Provider<Boolean>,
    ): TaskProvider<KiteConfigPlanTask> =
        root.tasks.register<KiteConfigPlanTask>("kitePlan") {
            colorEnabled.set(colorSupported)
        }

    private fun configurePlanTask(
        root: Project,
        ext: KiteConfigExtension,
        planTask: TaskProvider<KiteConfigPlanTask>,
        detectedApplications: List<String>,
        androidResourceDirectories: List<java.io.File>,
    ) {
        val plan = root.provider {
            KiteOperationPlanner.derive(root, ext, detectedApplications, androidResourceDirectories)
        }
        planTask.configure {
            operations.set(plan.map { derived -> derived.operations.map { it.description } })
            notes.set(
                plan.map { derived ->
                    derived.operations.map { "${it.description}: ${derived.reasons.getValue(it)}" }
                },
            )
            mutationPaths.set(plan.map { it.paths })
            selectedTargets.set(plan.map { it.targets })
            policies.set(plan.map { it.policies })
            exactChanges.set(root.provider { emptyList() })
        }
    }

    /**
     * Hooks automatic application onto each platform's ordinary build.
     *
     * Android app `preBuild` pulls the Android plan; the iOS framework link and
     * embed tasks pull the iOS plan. Kotlin IDE import also pulls the iOS plan,
     * before Xcode loads its project. Android builds stay platform-scoped and do
     * not edit iOS source files.
     *
     * Gates stay lazy, so a project that never sets `autoApply` adds no
     * dependency at all.
     */
    private fun wireAutomaticApplication(root: Project, ext: KiteConfigExtension) {
        root.allprojects(Action {
            tasks.matching { it.name == "preBuild" }.configureEach(Action {
                if (ext.effectiveAutoRewriteLogo.get()) dependsOn(root.tasks.named("kiteApplyAndroid"))
            })
            tasks.matching {
                it.name == "prepareKotlinIdeaImport" ||
                    it.name == "embedAndSignAppleFrameworkForXcode" ||
                    (it.name.startsWith("link") && it.name.contains("Framework") && it.name.contains("Ios"))
            }.configureEach(Action {
                if (ext.effectiveAutoRewriteXcode.get()) dependsOn(root.tasks.named("kiteApplyIos"))
            })
        })
    }

    private fun <T : Any> Project.resilientValue(compute: () -> T?): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return provider { runCatching(compute).getOrNull() } as Provider<T>
    }

    private fun KiteConfigDiagnosticTaskBase.bindDiagnosticInputs(
        root: Project,
        ext: KiteConfigExtension,
        resolvedAndroidAppDirectory: org.gradle.api.file.DirectoryProperty,
        colorSupported: Provider<Boolean>,
    ) {
        colorEnabled.set(colorSupported)
        propagateAppName.set(ext.appNameFlowsAnywhere())
        appName.set(ext.effectiveAppName)
        propagateBundleId.set(ext.appIdFlowsAnywhere())
        iosBundleId.set(ext.iosBundleId)
        propagateVersion.set(ext.effectivePropagateVersion)
        versionName.set(ext.effectiveVersion)
        hasVersionCodeOverride.set(ext.effectiveHasExplicitVersionCode)
        resolvedVersionCode.set(root.resilientValue { ext.effectiveAndroidVersionCode.orNull })
        propagateLocaleList.set(ext.localesFlowsAnywhere())
        locales.set(ext.effectiveLocales)
        filterAndroidResources.set(ext.effectiveFilterAndroidResources)
        syncIos.set(ext.effectiveSyncIos)
        sanitizeIosProject.set(ext.effectiveSanitizeIosProject)
        propagateLogo.set(ext.effectivePropagateLogo)
        cleanupLegacyLogoArtifacts.set(ext.effectiveTakeOverLegacyIcons)
        iosMarketingVersion.set(ext.effectiveIosMarketingVersion)
        iosBuildNumber.set(root.resilientValue { ext.effectiveIosBuildNumber.orNull })
        // Read from the Xcode project, which already states it. A second copy in
        // kiteConfig { } could only ever disagree with the first.
        iosDeploymentTarget.set(
            root.resilientValue {
                val pbxproj = ext.effectiveIosPbxproj.orNull?.asFile?.takeIf(java.io.File::isFile)
                pbxproj?.let { iosDeploymentTargetFrom(it.readText(), ext.effectiveIosTargets.get().toSet()) }
            },
        )
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
                    background = ext.effectiveLogoBackground.orNull,
                    backgroundColor = ext.effectiveLogoBackgroundColor.orNull,
                    parameters = mapOf(
                        "emitMonochrome" to (ext.android.compileSdk.orNull?.let { it >= 33 } == true).toString(),
                        "safeZoneRatio" to ext.effectiveLogoSafeZone.get().toString(),
                    ),
                    overlay = ext.effectiveLogoOverlayFor(KitePlatform.ANDROID).orNull?.asFile,
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
                    background = ext.effectiveLogoBackground.orNull,
                    backgroundColor = ext.effectiveLogoBackgroundColor.orNull,
                    overlay = ext.effectiveLogoOverlayFor(KitePlatform.IOS).orNull?.asFile,
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
        propagateDesktop.set(ext.effectiveDesktopEnabled)
        desktopIconsExplicit.set(ext.logoFlowsTo(KitePlatform.DESKTOP))
        splashAndroid.set(ext.effectiveAndroidSplash)
        splashDesktop.set(ext.effectiveDesktopSplash)
        splashIosArmed.set(ext.effectiveIosSplash)
        splashIos.set(ext.effectiveIosSplash)
        splashThemeSet.set(ext.effectiveSplashAndroidTheme.map { true }.orElse(false))
        versionGuardsIgnored.set(ext.effectiveIgnoreVersionGuards)
        // The same layer an ordinary build runs, wrapped so a broken model is
        // reported rather than thrown while the report is being configured.
        modelFindings.set(root.provider { runCatching { collectModelFindings(ext) }.getOrDefault(emptyList()) })
        // The debug values the Xcode rewrite and the debug catalog installer use,
        // so the doctor expects exactly what kiteApplyIos writes.
        iosDebugConfigurations.set(ext.effectiveIosDebugConfigurations)
        iosDebugBundleId.set(
            ext.effectiveDebugAppIdFor(KitePlatform.IOS).map { id -> id.takeIf { ext.debugAppIdFlowsTo(KitePlatform.IOS).get() } },
        )
        iosDebugAppName.set(
            ext.effectiveDebugAppNameFor(KitePlatform.IOS).map { name -> name.takeIf { ext.debugAppNameFlowsTo(KitePlatform.IOS).get() } },
        )
        iosDebugAppIconName.set(
            ext.iosDebugAppIconDirectory.map { dir ->
                if (ext.debugLogoFlowsTo(KitePlatform.IOS).get()) iosAppIconCatalogName(dir.asFile.name) else null
            },
        )
        propagateIosDebugLogo.set(ext.debugLogoFlowsTo(KitePlatform.IOS))
        iosDebugAppiconsetDir.set(ext.iosDebugAppIconDirectory)
        iosDebugLogoInputFingerprint.set(root.provider {
            if (!ext.effectiveSyncIos.get() || !ext.debugLogoFlowsTo(KitePlatform.IOS).get()) {
                "disabled"
            } else {
                logoInputFingerprintForFiles(
                    rendererVersion = SyncIosLogoTask.RENDERER_FINGERPRINT_VERSION,
                    foreground = ext.effectiveDebugLogoForegroundFor(KitePlatform.IOS).get().asFile,
                    background = ext.effectiveDebugLogoBackgroundImageFor(KitePlatform.IOS).orNull,
                    backgroundColor = ext.effectiveDebugLogoBackgroundColorFor(KitePlatform.IOS).orNull,
                    overlay = ext.effectiveDebugLogoOverlayFor(KitePlatform.IOS).orNull?.asFile,
                )
            }
        })
        autoRewrites.set(
            ext.effectiveAutoRewriteLogo.zip(ext.effectiveAutoRewriteXcode) { a, b -> a || b },
        )
        androidManifestTheme.set(root.resilientValue {
            val appDir = resolvedAndroidAppDirectory.orNull ?: ext.effectiveAndroidAppDirectory.orNull
            if (!ext.effectiveAndroidSplash.get() || appDir == null) {
                null
            } else {
                val manifest = appDir.file("src/main/AndroidManifest.xml").asFile
                if (manifest.isFile) androidManifestApplicationTheme(manifest.readText()) else null
            }
        })
        composeOnClasspath.set(COMPOSE_ON_CLASSPATH)
        if (COMPOSE_ON_CLASSPATH) runtimeComposeVersion()?.let(this.activeComposeVersion::set)
        desktopApplicationProjects.set(ext.effectiveDesktopApps)
        appId.set(ext.appId)
        desktopBundleId.set(ext.effectiveAppIdFor(KitePlatform.DESKTOP))
        desktopLinuxPackageName.set(ext.desktop.linuxPackageName)
        desktopDeriveUpgradeUuid.set(ext.desktop.deriveUpgradeUuid.orElse(false))
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
     * `locales` alone is not value-frozen here: its convention walks the shared
     * module's compose resources, and when the shared module comes from sole-KMP
     * detection it is only known at projectsEvaluated, after this freeze runs.
     * Freezing the value here, or on first read, would lock an empty list before
     * detection could speak. Mutation is still blocked; projectsEvaluated
     * finalizes the value once it is knowable.
     */
    /**
     * Every rule the model has to satisfy, collected rather than thrown.
     *
     * One layer, two renderings: an ordinary build fails on the first finding,
     * and the diagnostics report all of them. They used to be separate code, so
     * kiteCheck could pass a configuration that a build refused.
     */
    internal fun collectModelFailures(ext: KiteConfigExtension): List<Throwable> {
        val findings = mutableListOf<Throwable>()
        fun check(rule: () -> Unit) {
            runCatching(rule).exceptionOrNull()?.let(findings::add)
        }
        check {
                // Validate the resolved model once, before any explicit mutation task runs.
                val buildConfigIdentity = ext.effectiveBuildConfigEnabled.get() && ext.buildConfig.includeIdentity.get()
                val usesAppName = ext.appNameFlowsAnywhere().get() || buildConfigIdentity
                val usesBundleId = ext.appIdFlowsAnywhere().get() || buildConfigIdentity
                val usesVersion = ext.effectivePropagateVersion.get() || buildConfigIdentity
                val usesLocaleModel = ext.localesFlowsAnywhere().get() || ext.effectiveFilterAndroidResources.get() || buildConfigIdentity
                val usesSharedSelection = ext.effectiveBuildConfigEnabled.get() || ext.effectiveNativeOptInsEnabled.get() ||
                    ext.effectiveIoWorkerEnabled.get() || usesLocaleModel
                val usesAndroidApplicationSelection =
                    (ext.appNameFlowsAnywhere().get() && ext.effectiveAppName.isPresent) ||
                        (ext.appIdFlowsAnywhere().get() && ext.appId.isPresent) ||
                        (ext.effectivePropagateVersion.get() &&
                            (ext.effectiveVersion.isPresent || ext.effectiveAndroidVersionCode.isPresent)) ||
                        ext.effectiveFilterAndroidResources.get() || ext.effectiveAndroidLogoInstall.get()
                if (usesAppName) ext.effectiveAppName.orNull?.let(::validateAppName)
                if (usesVersion) ext.effectiveVersion.orNull?.let(::validateVersionName)
                if ((ext.effectivePropagateVersion.get() || buildConfigIdentity) &&
                    ext.effectiveVersion.isPresent && !ext.effectiveAndroidVersionCode.isPresent
                ) {
                    ext.effectiveAndroidVersionCode.get()
                }
                // Each store's rule belongs to the platform that has it. Checking both
                // whenever an identifier reached anywhere meant an iOS-only identifier
                // still had to satisfy Android, which rejects a hyphen that Apple allows.
                // The gate comes first: asking whether the code is present evaluates
                // the scheme, and a version nobody asked to encode must never be run
                // through it.
                if (ext.versionFlowsTo(KitePlatform.ANDROID).get() || buildConfigIdentity) {
                    ext.effectiveAndroidVersionCode.orNull?.let(::validateVersionCode)
                }
                if (ext.appId.isPresent) {
                    if (ext.appIdFlowsTo(KitePlatform.ANDROID).get() || buildConfigIdentity) {
                        validateAndroidApplicationId(ext.androidApplicationId.get())
                    }
                    if (ext.appIdFlowsTo(KitePlatform.IOS).get() || buildConfigIdentity) {
                        validateAppleBundleId(ext.iosBundleId.get())
                    }
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
                        throw GradleException("kiteConfig { modules { androidApps } } contains duplicate project paths.")
                    }
                }
                if (ext.effectiveSyncIos.get()) {
                    val iosTargetNames = ext.effectiveIosTargets.get()
                    if (iosTargetNames.distinct().size != iosTargetNames.size ||
                        iosTargetNames.any { it.isBlank() || it.any(Char::isISOControl) }
                    ) {
                        throw GradleException("kiteConfig { ios { xcodeTargets } } must contain unique, non-blank names without controls.")
                    }
                    if (iosTargetNames.size > 1 && ext.appIdFlowsAnywhere().get() && ext.appId.isPresent) {
                        throw GradleException(
                            "kiteConfig refuses to assign one Apple bundle identifier to multiple application targets " +
                                "(${iosTargetNames.joinToString()}). Select one target, or stop the id flowing to iOS " +
                                "with ios { appId { enabled = false } }."
                        )
                    }
                }
                ext.effectiveJvmTarget.orNull?.let(::validateJavaVersion)
                if (ext.effectiveNativeOptInsEnabled.get()) {
                    val interopProjects = ext.effectiveNativeOptInProjects.get()
                    ext.effectiveNativeOptInMarkers.get().forEach(::validateOptInMarker)
                    interopProjects.forEach { validateGradleProjectPath(it, "optIns { projects }") }
                    if (interopProjects.distinct().size != interopProjects.size) {
                        throw GradleException("kiteConfig { optIns { projects } } contains duplicate project paths.")
                    }
                }
                // The locale and shared-project checks moved to projectsEvaluated: both can
                // be satisfied by sole-KMP detection, whose census completes only there.
                if (ext.effectiveBuildConfigEnabled.get() && ext.buildConfig.includeIdentity.get()) {
                    val missing = buildList {
                        if (!ext.effectiveAppName.isPresent) add("appName")
                        if (!ext.effectiveVersion.isPresent) add("version")
                        if (!ext.effectiveAndroidVersionCode.isPresent) add("version or ${buildNumberProperty("android")}")
                        if (!ext.appId.isPresent) add("appId")
                    }
                    if (missing.isNotEmpty()) {
                        throw GradleException(
                            "kiteConfig buildConfig.includeIdentity requires a complete identity; missing " +
                                missing.joinToString() + ". Set includeIdentity=false for fields-only generation."
                        )
                    }
                }
                if (ext.effectiveSyncIos.get() && ext.effectivePropagateSharedModule.get()) {
                    if (!ext.effectiveIosSharedModuleName.isPresent || !ext.effectiveIosPreviousSharedModuleName.isPresent) {
                        throw GradleException(
                            "kiteConfig shared-module migration needs both names. Call " +
                                "ios { renameSharedModule(from = \"old\", to = \"new\") }. " +
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
                // Debug builds. An identifier equal to the release one could not
                // install beside it, which is the whole point of the topic.
                for (platform in listOf(KitePlatform.ANDROID, KitePlatform.IOS)) {
                    val label = platform.name.lowercase()
                    if (ext.debugAppIdRequestedFor(platform).get() && !ext.effectiveAppIdFor(platform).isPresent) {
                        throw GradleException(
                            "kiteConfig { debug { appId } } on $label needs the shared appId, because a debug " +
                                "identifier is a variation of the release one.",
                        )
                    }
                    if (ext.debugAppIdFlowsTo(platform).get()) {
                        val debugId = ext.effectiveDebugAppIdFor(platform).get()
                        if (platform == KitePlatform.ANDROID) validateAndroidApplicationId(debugId) else validateAppleBundleId(debugId)
                        if (debugId == ext.effectiveAppIdFor(platform).get()) {
                            throw GradleException(
                                "kiteConfig { debug { appId } } resolves to the release identifier on $label " +
                                    "($debugId), so the two builds could not be installed side by side. " +
                                    "Give the debug build a suffix, for example appId { suffix = \".debug\" }.",
                            )
                        }
                    }
                    if (ext.debugAppNameFlowsTo(platform).get()) {
                        val debugName = ext.effectiveDebugAppNameFor(platform).get()
                        if (debugName.isBlank() || debugName.length > 255 || debugName.any(Char::isISOControl)) {
                            throw GradleException(
                                "kiteConfig { debug { appName } } must be non-blank, at most 255 characters, " +
                                    "and contain no controls.",
                            )
                        }
                    }
                    if (ext.debugLogoFlowsTo(platform).get()) {
                        if (!ext.effectiveDebugLogoForegroundFor(platform).isPresent) {
                            throw GradleException(
                                "kiteConfig { debug { logo } } has no foreground on $label. Set logo { foreground } " +
                                    "at the root, or debug { logo { foreground } }.",
                            )
                        }
                        if (!ext.effectiveDebugLogoBackgroundFor(platform).isPresent) {
                            throw GradleException(
                                "kiteConfig { debug { logo } } has no background on $label. Set " +
                                    "background = color(\"#RRGGBB\") or background = image(file(\"...\")).",
                            )
                        }
                        ext.effectiveDebugLogoBackgroundColorFor(platform).orNull?.let(::validateLogoBackgroundColorHex)
                        ext.effectiveDebugLogoScaleFor(platform).orNull?.let(::validateLogoSafeZoneRatio)
                    }
                }
                val debugBuildTypes = ext.effectiveAndroidDebugBuildTypes.get()
                if (debugBuildTypes.isEmpty() || debugBuildTypes.distinct().size != debugBuildTypes.size ||
                    debugBuildTypes.any { it.isBlank() || it.any(Char::isISOControl) }
                ) {
                    throw GradleException(
                        "kiteConfig { android { debug { buildTypes } } } must contain unique, non-blank names without controls.",
                    )
                }
                val debugConfigurations = ext.effectiveIosDebugConfigurations.get()
                if (debugConfigurations.isEmpty() || debugConfigurations.distinct().size != debugConfigurations.size ||
                    debugConfigurations.any { it.isBlank() || it.any(Char::isISOControl) }
                ) {
                    throw GradleException(
                        "kiteConfig { ios { debug { configurations } } } must contain unique, non-blank names without controls.",
                    )
                }
                // Safe-zone ratio sanity.
                if (ext.effectiveAndroidLogoInstall.get() && ext.effectiveLogoSafeZone.isPresent) {
                    validateLogoSafeZoneRatio(ext.effectiveLogoSafeZone.get())
                }
                // Logo: FG must be paired with exactly one BG source (PNG or color).
                // Only validate when logo propagation is on, otherwise a stray FG with
                // propagateLogo = false shouldn't fail the build.
                if (ext.effectivePropagateLogo.get()) {
                    // The background is one choice now, so "both set" cannot happen.
                    val fgSet = ext.effectiveLogoForeground.isPresent
                    val backgroundSet = ext.logo.background.isPresent
                    if (!fgSet && !backgroundSet) {
                        throw GradleException(
                            "kiteConfig { logo { } } requires foreground plus a background: " +
                                "background = color(\"#RRGGBB\") or background = image(file(\"...\")).",
                        )
                    }
                    if (fgSet && !backgroundSet) {
                        throw GradleException(
                            "kiteConfig { logo { foreground } } is set but no background. Add " +
                                "background = color(\"#RRGGBB\") or background = image(file(\"...\")).",
                        )
                    }
                    if (!fgSet) {
                        throw GradleException(
                            "kiteConfig { logo } has a background but no foreground. Set " +
                                "logo { foreground }, or remove the background.",
                        )
                    }
                    ext.effectiveLogoBackgroundColor.orNull?.let(::validateLogoBackgroundColorHex)
                    // The single-size universal catalog needs iOS 12. That fact lives
                    // in the Xcode project, so it is read rather than asked for, and
                    // only when the iOS icon is actually going to be installed.
                    if (ext.effectiveIosLogoInstall.get()) {
                        ext.effectiveIosPbxproj.orNull?.asFile
                            ?.takeIf(java.io.File::isFile)
                            ?.let { iosDeploymentTargetFrom(it.readText(), ext.effectiveIosTargets.get().toSet()) }
                            ?.let(::validateUniversalAppIconDeploymentTarget)
                    }
                }

                // Legacy takeover is validated as part of the Android installer's
                // transaction. The standalone cleanup task remains available for an
                // explicitly requested one-shot recovery workflow.

        }
        return findings
    }

    /** The same failures, rendered for a report rather than thrown. */
    internal fun collectModelFindings(ext: KiteConfigExtension): List<String> =
        collectModelFailures(ext).map(::diagnosticExceptionSummary)

    private fun finalizeModel(ext: KiteConfigExtension) {
        modelValues(ext).forEach { value ->
            if (value === ext.localesScope.tags) {
                // Not finalizeValueOnRead: an early reader would pin an empty list
                // before locale detection runs. disallowChanges still blocks any
                // late mutation; projectsEvaluated finalizes the value itself.
                value.disallowChanges()
            } else {
                value.finalizeValue()
            }
        }
    }

    /** Prevent late mutation without realizing provider-backed diagnostic values. */
    private fun disallowModelChanges(ext: KiteConfigExtension) {
        modelValues(ext).forEach { it.disallowChanges() }
    }

    /**
     * Every configurable input behind the DSL, new and pre-3.0 alike.
     *
     * Derived `effective*` views are deliberately absent: they are read-only
     * providers over these values, so locking the sources locks the views too.
     */
    @OptIn(DiscouragedKiteApi::class)
    private fun modelValues(ext: KiteConfigExtension): List<HasConfigurableValue> =
        listOf(
            ext.appName, ext.version, ext.appId,
            ext.jvmScope.target, ext.jvmScope.toolchain,
            ext.android.jvmDetails.target, ext.android.jvmDetails.toolchain,
            ext.desktop.jvmDetails.target, ext.desktop.jvmDetails.toolchain,
            ext.dryRun, ext.backups, ext.ignoreVersionGuards,
            ext.logoConfigured,
            ext.buildConfig.enabled, ext.optIns.enabled, ext.web.ioWorker.enabled,
            ext.skipped, ext.allowed,
        ) + listOf(
            ext.localesScope.tags,
            ext.logo.foreground, ext.logo.background, ext.logo.foregroundScale, ext.logo.overlay,
            ext.logo.enabled,
            ext.splash.enabled,
            ext.splash.image, ext.splash.backgroundColor,
            ext.splash.dark.image, ext.splash.dark.backgroundColor, ext.splash.dark.enabled,
            ext.android.androidSplash.theme,
            ext.modules.shared, ext.modules.androidApps,
            ext.modules.androidAppDirectory, ext.modules.composeResources,
        ) + listOf(
            ext.android.compileSdk, ext.android.minSdk,
            ext.android.targetSdk, ext.android.ndk,
            ext.versionScope.schemeProp, ext.versionScope.rebuild,
            ext.android.androidLocales.filterResources,
        ) + KitePlatform.entries.flatMap { platform ->
            val block = ext.platformExtension(platform)
            listOf(
                block.appName, block.appId,
                block.appNameDetails.enabled,
                block.appIdDetails.suffix, block.appIdDetails.enabled,
                block.version,
                block.versionDetails.schemeProp, block.versionDetails.rebuild,
                block.versionDetails.buildNumber, block.versionDetails.publishedBuildNumber,
                block.versionDetails.enabled,
                block.localesDetails.tags, block.localesDetails.enabled,
                block.logoDetails.foreground, block.logoDetails.background,
                block.logoDetails.foregroundScale, block.logoDetails.overlay, block.logoDetails.enabled,
                block.splashDetails.enabled, block.splashDetails.image,
                block.splashDetails.backgroundColor,
                block.splashDetails.dark.image, block.splashDetails.dark.backgroundColor,
                block.splashDetails.dark.enabled,
                block.autoApply,
            )
        } + listOf(
            ext.ios.pbxproj, ext.ios.podfile,
            ext.ios.appDirectory, ext.ios.appIconDirectory,
        ) + listOf(
            ext.ios.infoPlist.path, ext.ios.infoPlist.nonExemptEncryption,
            ext.ios.infoPlist.proMotion, ext.ios.xcodeTargetNames,
            ext.ios.previousSharedModuleName, ext.ios.newSharedModuleName,
        ) + listOf(
            ext.autoApply, ext.desktop.desktopLogo.roundMac,
            ext.optIns.builtIns, ext.optIns.markers, ext.optIns.projects,
        ) + listOf(
            ext.web.ioWorker.declared,
            ext.web.ioWorker.targets, ext.web.ioWorker.projects, ext.web.ioWorker.packageName,
            ext.buildConfig.packageName, ext.buildConfig.className,
            ext.buildConfig.includeIdentity, ext.buildConfig.allowBuildCache, ext.buildConfig.fields,
        ) + listOf(
            ext.desktop.configured, ext.desktop.linuxPackageName,
            ext.desktop.deriveUpgradeUuid, ext.modules.desktopApps,
        ) + debugModelValues(ext.debug) +
            debugModelValues(ext.android.androidDebug) + listOf(ext.android.androidDebug.buildTypes) +
            debugModelValues(ext.ios.iosDebug) + listOf(ext.ios.iosDebug.configurations)

    private fun debugModelValues(scope: KiteDebugScope): List<HasConfigurableValue> = listOf(
        scope.appName, scope.appId,
        scope.appNameDetails.enabled,
        scope.appIdDetails.suffix, scope.appIdDetails.enabled,
        scope.logoDetails.foreground, scope.logoDetails.background,
        scope.logoDetails.foregroundScale, scope.logoDetails.overlay, scope.logoDetails.enabled,
    )

    public companion object {
        private const val MIN_GRADLE = "8.5"
        private val RESILIENT_DIAGNOSTIC_TASKS = setOf(
            "kiteVerify",
            "kiteDoctor",
            "kiteCheck",
            "kitePlan",
        )

        /**
         * Whether the (compileOnly) Kotlin Gradle plugin classes are loadable from
         * kiteconfig's own classloader. False when the consumer declares
         * kotlin("multiplatform") only in a subproject, which puts KGP in a sibling
         * classloader. Calling into KGP-typed methods would then throw
         * NoClassDefFoundError, so those features are guarded on this.
         */
        internal val KGP_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension",
                false,
                KiteConfigPlugin::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

        /**
         * Whether the (compileOnly) Compose Gradle plugin classes are loadable from
         * kiteconfig's own classloader. False when the consumer declares
         * org.jetbrains.compose only in a subproject, which puts it in a sibling
         * classloader.
         */
        internal val COMPOSE_ON_CLASSPATH: Boolean = try {
            Class.forName(
                "org.jetbrains.compose.desktop.DesktopExtension",
                false,
                KiteConfigPlugin::class.java.classLoader,
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
                KiteConfigPlugin::class.java.classLoader,
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
                KiteConfigPlugin::class.java.classLoader,
            ).`package`.implementationVersion
        }.getOrNull()

        /**
         * The published `compose-gradle-plugin` jar carries no `Implementation-Version`
         * manifest entry, so `Package.implementationVersion` (the mechanism AGP and KGP
         * use above) always reads null here. Compose instead bakes its version into a
         * `const val` on this generated object, which reflection reads as a plain
         * static field with no instance required.
         */
        private fun runtimeComposeVersion(): String? = runCatching {
            Class.forName(
                "org.jetbrains.compose.ComposeBuildConfig",
                false,
                KiteConfigPlugin::class.java.classLoader,
            ).getField("composeGradlePluginVersion").get(null) as? String
        }.getOrNull()

        private fun runtimeAgpVersion(): String? = runCatching {
            val versionClass = Class.forName(
                "com.android.build.api.AndroidPluginVersion",
                false,
                KiteConfigPlugin::class.java.classLoader,
            )
            val current = versionClass.getMethod("getCurrent").invoke(null)
            versionClass.getMethod("getVersion").invoke(current)?.toString()
        }.getOrNull()
    }
}
