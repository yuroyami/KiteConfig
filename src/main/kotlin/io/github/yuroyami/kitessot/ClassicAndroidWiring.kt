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
 * module-local `applicationId` / `versionName` / `compileSdk`. This matches the
 * KMP-native library path ([KmpAndroidLibraryWiring]) and makes the single source
 * of truth authoritative for every Android module shape. Leave a field unset in
 * `kiteSsot { }` to keep whatever the module declares.
 */
internal object ClassicAndroidWiring {

    fun wireApplication(project: Project, ext: KiteSsotExtension) {
        val components = project.extensions
            .findByType(ApplicationAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            val selectedApplications = ext.effectiveAndroidApps.get()
            val receivesAppScopedValues =
                selectedApplications.isEmpty() || project.path in selectedApplications
            if (receivesAppScopedValues) {
                if (ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) {
                    dc.applicationId = ext.androidApplicationId.get()
                }
                if (ext.effectivePropagateVersion.get()) {
                    // versionName and versionCode are independent. A lone
                    // versionCodeOverride still increments the build number.
                    if (ext.effectiveVersion.isPresent) dc.versionName = ext.effectiveVersion.get()
                    ext.effectiveAndroidVersionCode.orNull?.let { dc.versionCode = it }
                }
                if (ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) {
                    dc.manifestPlaceholders["appName"] = ext.effectiveAppName.get()
                }
                if (ext.effectiveFilterAndroidResources.get()) {
                    val l = ext.canonicalLocales.get().map(::bcp47ToAndroidQualifier)
                    require(l.isNotEmpty()) {
                        "[kiteSsot] android { filterResourcesToLocales } requires at least one canonical locale."
                    }
                    android.androidResources.localeFilters.clear()
                    android.androidResources.localeFilters.addAll(l)
                }
            }
            if (ext.effectiveApplySdkLevels.get()) {
                val sdk = ext.android
                if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
                if (sdk.ndk.isPresent) android.ndkVersion = sdk.ndk.get()
                if (sdk.minSdk.isPresent) dc.minSdk = sdk.minSdk.get()
                if (sdk.targetSdk.isPresent) dc.targetSdk = sdk.targetSdk.get()
            }
            if (ext.effectiveJvmTarget.isPresent) {
                val jv = JavaVersion.toVersion(validateJavaVersion(ext.effectiveJvmTarget.get()))
                android.compileOptions.sourceCompatibility = jv
                android.compileOptions.targetCompatibility = jv
            }
        }
    }

    fun wireLibrary(project: Project, ext: KiteSsotExtension) {
        val components = project.extensions
            .findByType(LibraryAndroidComponentsExtension::class.java) ?: return
        components.finalizeDsl { android ->
            val dc = android.defaultConfig
            if (ext.effectiveApplySdkLevels.get()) {
                val sdk = ext.android
                if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
                if (sdk.ndk.isPresent) android.ndkVersion = sdk.ndk.get()
                if (sdk.minSdk.isPresent) dc.minSdk = sdk.minSdk.get()
                // Library modules have no targetSdk (AGP removed it).
            }
            if (ext.effectiveJvmTarget.isPresent) {
                val jv = JavaVersion.toVersion(validateJavaVersion(ext.effectiveJvmTarget.get()))
                android.compileOptions.sourceCompatibility = jv
                android.compileOptions.targetCompatibility = jv
            }
        }
    }

}
