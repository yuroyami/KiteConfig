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
            val selectedApplications = ext.androidApplicationProjects.get()
            val receivesAppScopedValues =
                selectedApplications.isEmpty() || project.path in selectedApplications
            if (receivesAppScopedValues) {
                if (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) {
                    dc.applicationId = ext.androidApplicationId.get()
                }
                if (ext.propagateVersion.get()) {
                    // versionName and versionCode are independent. A lone
                    // versionCodeOverride still increments the build number.
                    if (ext.versionName.isPresent) dc.versionName = ext.versionName.get()
                    ext.versionCode.orNull?.let { dc.versionCode = it }
                }
                if (ext.propagateAppName.get() && ext.appName.isPresent) {
                    dc.manifestPlaceholders["appName"] = ext.appName.get()
                }
                if (ext.filterAndroidResources.get()) {
                    val l = ext.canonicalLocales.get().map(::bcp47ToAndroidQualifier)
                    require(l.isNotEmpty()) {
                        "[kiteSsot] filterAndroidResources=true requires at least one canonical locale."
                    }
                    android.androidResources.localeFilters.clear()
                    android.androidResources.localeFilters.addAll(l)
                }
            }
            if (ext.propagateAndroidSdk.get()) {
                val sdk = ext.android
                if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
                if (sdk.ndkVersion.isPresent) android.ndkVersion = sdk.ndkVersion.get()
                if (sdk.minSdk.isPresent) dc.minSdk = sdk.minSdk.get()
                if (sdk.targetSdk.isPresent) dc.targetSdk = sdk.targetSdk.get()
            }
            if (ext.javaVersion.isPresent) {
                val jv = JavaVersion.toVersion(validateJavaVersion(ext.javaVersion.get()))
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
            if (ext.propagateAndroidSdk.get()) {
                val sdk = ext.android
                if (sdk.compileSdk.isPresent) android.compileSdk = sdk.compileSdk.get()
                if (sdk.ndkVersion.isPresent) android.ndkVersion = sdk.ndkVersion.get()
                if (sdk.minSdk.isPresent) dc.minSdk = sdk.minSdk.get()
                // Library modules have no targetSdk (AGP removed it).
            }
            if (ext.javaVersion.isPresent) {
                val jv = JavaVersion.toVersion(validateJavaVersion(ext.javaVersion.get()))
                android.compileOptions.sourceCompatibility = jv
                android.compileOptions.targetCompatibility = jv
            }
        }
    }

}
