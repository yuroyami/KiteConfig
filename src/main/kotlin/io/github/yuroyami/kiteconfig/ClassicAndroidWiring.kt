package io.github.yuroyami.kiteconfig

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Wiring for the classic AGP plugins: `com.android.application` and
 * `com.android.library`.
 *
 * Kept in its **own class** on purpose (same reason as [KmpAndroidLibraryWiring]):
 * the AGP DSL types appear in the `finalizeDsl { }` lambdas, which puts them into
 * synthetic method descriptors. Were those on [KiteConfigPlugin] itself, Gradle would
 * fail to *decorate* the plugin whenever AGP is absent from the classpath (the
 * iOS-only functional tests).
 *
 * **Authority:** the wiring runs inside AGP's `finalizeDsl` hook, i.e. AFTER the
 * module's own `android { }` block, so a value declared in `kiteConfig { }` overrides a
 * module-local `applicationId` / `versionName` / `compileSdk` — and supplies the
 * value outright when the module declares nothing, because `finalizeDsl` runs
 * before AGP validates its DSL. This matches the KMP-native library path
 * ([KmpAndroidLibraryWiring]) and makes the single source of truth authoritative
 * for every Android module shape. Leave a field unset in `kiteConfig { }` to keep
 * whatever the module declares.
 *
 * The wiring also runs on the diagnostic invocations (`kiteVerify`,
 * `kiteDoctor`, `kiteCheck`, `kitePlan`): AGP validates its DSL on
 * every invocation, so a consumer that declares `compileSdk` only in
 * `kiteConfig { }` needs the wiring alive there too. On those runs each value
 * group goes through [wireValueGroup], which skips a failing provider so the
 * diagnostic can report it as a finding instead of dying in configuration.
 *
 * When a module still declares a value KiteConfig replaces with something
 * different, [ConfigDriftLog] says so once per project: that declaration is dead
 * code wearing a misleading face value.
 */
internal object ClassicAndroidWiring {

    fun wireApplication(project: Project, ext: KiteConfigExtension, resilient: Boolean) {
        val components = project.extensions
            .findByType(ApplicationAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            val drift = ConfigDriftLog(project)

            // Fails closed: when the selection cannot resolve on a diagnostic run,
            // no app-scoped value is written anywhere.
            var receivesAppScopedValues = false
            project.wireValueGroup(resilient, "the application selection") {
                val selectedApplications = ext.effectiveAndroidApps.get()
                receivesAppScopedValues =
                    selectedApplications.isEmpty() || project.path in selectedApplications
            }

            if (receivesAppScopedValues) {
                project.wireValueGroup(resilient, "applicationId") {
                    if (ext.idFlowsTo(KitePlatform.ANDROID).get() && ext.id.isPresent) {
                        val applied = ext.androidApplicationId.get()
                        drift.observe("applicationId", dc.applicationId, applied)
                        dc.applicationId = applied
                    }
                }
                project.wireValueGroup(resilient, "the version values") {
                    if (ext.versionFlowsTo(KitePlatform.ANDROID).get()) {
                        // versionName and versionCode are independent. A lone
                        // versionCodeOverride still increments the build number.
                        if (ext.effectiveVersion.isPresent) {
                            val applied = ext.effectiveVersion.get()
                            drift.observe("versionName", dc.versionName, applied)
                            dc.versionName = applied
                        }
                        ext.effectiveAndroidVersionCode.orNull?.let { applied ->
                            drift.observe("versionCode", dc.versionCode, applied)
                            dc.versionCode = applied
                        }
                    }
                }
                project.wireValueGroup(resilient, "the appName placeholder") {
                    if (ext.appNameFlowsTo(KitePlatform.ANDROID).get() && ext.effectiveAppNameFor(KitePlatform.ANDROID).isPresent) {
                        dc.manifestPlaceholders["appName"] = ext.effectiveAppNameFor(KitePlatform.ANDROID).get()
                    }
                }
                project.wireValueGroup(resilient, "the locale filters") {
                    if (ext.effectiveFilterAndroidResources.get()) {
                        val l = ext.canonicalLocales.get().map(::bcp47ToAndroidQualifier)
                        require(l.isNotEmpty()) {
                            "[kiteConfig] android { filterResourcesToLocales } requires at least one canonical locale."
                        }
                        android.androidResources.localeFilters.clear()
                        android.androidResources.localeFilters.addAll(l)
                    }
                }
            }
            project.wireValueGroup(resilient, "the SDK levels") {
                if (ext.effectiveApplySdkLevels.get()) {
                    val sdk = ext.android
                    if (sdk.compileSdk.isPresent) {
                        val applied = sdk.compileSdk.get()
                        drift.observe("compileSdk", android.compileSdk, applied)
                        android.compileSdk = applied
                    }
                    if (sdk.ndk.isPresent) android.ndkVersion = sdk.ndk.get()
                    if (sdk.minSdk.isPresent) {
                        val applied = sdk.minSdk.get()
                        drift.observe("minSdk", dc.minSdk, applied)
                        dc.minSdk = applied
                    }
                    if (sdk.targetSdk.isPresent) {
                        val applied = sdk.targetSdk.get()
                        drift.observe("targetSdk", dc.targetSdk, applied)
                        dc.targetSdk = applied
                    }
                }
            }
            project.wireValueGroup(resilient, "the Java compatibility level") {
                if (ext.effectiveJvmTarget.isPresent) {
                    val jv = JavaVersion.toVersion(validateJavaVersion(ext.effectiveJvmTarget.get()))
                    android.compileOptions.sourceCompatibility = jv
                    android.compileOptions.targetCompatibility = jv
                }
            }
            drift.report()
        }
    }

    fun wireLibrary(project: Project, ext: KiteConfigExtension, resilient: Boolean) {
        val components = project.extensions
            .findByType(LibraryAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            val drift = ConfigDriftLog(project)
            project.wireValueGroup(resilient, "the SDK levels") {
                if (ext.effectiveApplySdkLevels.get()) {
                    val sdk = ext.android
                    if (sdk.compileSdk.isPresent) {
                        val applied = sdk.compileSdk.get()
                        drift.observe("compileSdk", android.compileSdk, applied)
                        android.compileSdk = applied
                    }
                    if (sdk.ndk.isPresent) android.ndkVersion = sdk.ndk.get()
                    if (sdk.minSdk.isPresent) {
                        val applied = sdk.minSdk.get()
                        drift.observe("minSdk", dc.minSdk, applied)
                        dc.minSdk = applied
                    }
                    // Library modules have no targetSdk (AGP removed it).
                }
            }
            project.wireValueGroup(resilient, "the Java compatibility level") {
                if (ext.effectiveJvmTarget.isPresent) {
                    val jv = JavaVersion.toVersion(validateJavaVersion(ext.effectiveJvmTarget.get()))
                    android.compileOptions.sourceCompatibility = jv
                    android.compileOptions.targetCompatibility = jv
                }
            }
            drift.report()
        }
    }

}

// -------------------------------------------------------------------- Splash

/**
 * Wires the Android splash onto one `com.android.application` module: the
 * generator task, its output as a generated res dir, and the `kiteSplashTheme`
 * manifest placeholder. Everything is gated on `splash { }` flowing to Android.
 *
 * Lives beside [ClassicAndroidWiring] for the reason its KDoc states: the AGP DSL
 * types stay in this file's descriptors and out of [KiteConfigPlugin]. Pass
 * `resilient = true` on the diagnostic invocations, as the value wiring does, so
 * an unresolvable provider is reported instead of killing configuration.
 */
internal fun wireAndroidSplash(project: Project, ext: KiteConfigExtension, resilient: Boolean = false) {
    val components = project.extensions
        .findByType(ApplicationAndroidComponentsExtension::class.java) ?: return
    components.finalizeDsl { android ->
        // Fails closed: when the gate cannot resolve on a diagnostic run, nothing
        // is registered and no resource directory is added. The app selection is
        // part of the gate because the placeholder is an app-scoped value, exactly
        // like appName.
        var splashFlows = false
        project.wireValueGroup(resilient, "the Android splash selection") {
            val selectedApplications = ext.effectiveAndroidApps.get()
            val selectedHere = selectedApplications.isEmpty() || project.path in selectedApplications
            splashFlows = selectedHere && ext.effectiveAndroidSplash.get()
        }
        if (!splashFlows) return@finalizeDsl

        val splashTask = project.tasks.register<GenerateAndroidSplashTask>("kiteInternalAndroidSplash") {
            image.set(ext.effectiveSplashImage)
            darkImage.set(ext.effectiveSplashDarkImage)
            backgroundColor.set(ext.effectiveSplashColor)
            darkBackgroundColor.set(ext.effectiveSplashDarkColor)
            theme.set(ext.effectiveSplashAndroidTheme)
            outputDir.set(project.layout.buildDirectory.dir("generated/kiteconfig/splash-res"))
        }
        project.wireValueGroup(resilient, "the generated splash resources") {
            // A provider carrying task provenance, never a resolved File: this is what
            // makes the ordinary resource merge depend on kiteInternalAndroidSplash.
            android.sourceSets.getByName("main").res.srcDir(splashTask.flatMap { it.outputDir })
        }
        project.wireValueGroup(resilient, "the kiteSplashTheme placeholder") {
            // The lambda form on purpose: AGP 9 narrowed the `defaultConfig` GETTER
            // descriptor, so reading the property would break on the AGP 8 floor the
            // way the value wiring does. Passing a lambda keeps one binary call for
            // both AGP lines, which is why this needs no floor adapter.
            android.defaultConfig {
                manifestPlaceholders["kiteSplashTheme"] = "@style/$ANDROID_SPLASH_STYLE"
            }
        }
    }
}
