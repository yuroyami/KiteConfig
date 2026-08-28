package io.github.yuroyami.kitessot

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * Wiring for the classic AGP plugins: `com.android.application` and
 * `com.android.library`.
 *
 * Kept in its **own class** on purpose (same reason as [KmpAndroidLibraryWiring]):
 * the AGP DSL types appear in the `finalizeDsl { }` lambdas, which puts them into
 * synthetic method descriptors. Were those on [KiteSsotPlugin] itself, Gradle would
 * fail to *decorate* the plugin whenever AGP is absent from the classpath (the
 * iOS-only functional tests).
 *
 * **Authority:** the wiring runs inside AGP's `finalizeDsl` hook, i.e. AFTER the
 * module's own `android { }` block, so an SSOT value in `kiteSsot { }` overrides a
 * module-local `applicationId` / `versionName` / `compileSdk` — and supplies the
 * value outright when the module declares nothing, because `finalizeDsl` runs
 * before AGP validates its DSL. This matches the KMP-native library path
 * ([KmpAndroidLibraryWiring]) and makes the single source of truth authoritative
 * for every Android module shape. Leave a field unset in `kiteSsot { }` to keep
 * whatever the module declares.
 *
 * The wiring also runs on the diagnostic invocations (`kiteVerify`,
 * `kiteDoctor`, `kiteCheck`, `kitePlan`): AGP validates its DSL on
 * every invocation, so a consumer that declares `compileSdk` only in
 * `kiteSsot { }` needs the wiring alive there too. On those runs each value
 * group goes through [wireValueGroup], which skips a failing provider so the
 * diagnostic can report it as a finding instead of dying in configuration.
 *
 * When a module still declares a value the SSOT replaces with something
 * different, [SsotDriftLog] says so once per project: that declaration is dead
 * code wearing a misleading face value.
 */
internal object ClassicAndroidWiring {

    fun wireApplication(project: Project, ext: KiteSsotExtension, resilient: Boolean) {
        val components = project.extensions
            .findByType(ApplicationAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            val drift = SsotDriftLog(project)

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
                            "[kiteSsot] android { filterResourcesToLocales } requires at least one canonical locale."
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

    fun wireLibrary(project: Project, ext: KiteSsotExtension, resilient: Boolean) {
        val components = project.extensions
            .findByType(LibraryAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            val drift = SsotDriftLog(project)
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
