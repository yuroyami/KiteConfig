package io.github.yuroyami.kmpssot

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Isolated wiring for AGP's `com.android.kotlin.multiplatform.library` plugin — the Android
 * target of a Kotlin Multiplatform module declared via `kotlin { androidLibrary { } }`
 * (or `android { }` under the AGP 9 `android.newDsl`).
 *
 * Kept in its **own class** on purpose. Its DSL type lives in AGP (a `compileOnly`
 * dependency), and referencing it inside a `finalizeDsl { }` lambda puts the type into a
 * method descriptor. Were that on [KmpSsotPlugin] itself, Gradle would fail to *decorate*
 * the plugin whenever AGP is absent from the classpath (e.g. the iOS-only functional tests).
 * By living here, the AGP type is only class-loaded when the KMP library plugin is actually
 * applied — which is exactly when AGP is present — so the core plugin stays AGP-free.
 *
 * Why [KotlinMultiplatformAndroidComponentsExtension.finalizeDsl] rather than mutating the
 * extension directly: unlike the classic `com.android.application`/`com.android.library`
 * plugins, this plugin does **not** create its DSL extension synchronously at apply time, so
 * reading it inside the `withId` callback finds nothing. `finalizeDsl` is AGP's sanctioned
 * hook that runs after the module's own DSL block but before variants lock — so the SSOT
 * value also wins over a module-local `compileSdk`.
 *
 * Only `compileSdk`/`minSdk` apply: the KMP library DSL has no `targetSdk` (libraries never
 * did) nor `ndkVersion`, so those are skipped even when set. Locale propagation also doesn't
 * apply here — the application module owns the locale list.
 */
internal object KmpAndroidLibraryWiring {

    fun apply(project: Project, ext: KmpSsotExtension) {
        if (!ext.propagateAndroidSdk.get()) return
        val sdk = ext.android
        if (!sdk.compileSdk.isPresent && !sdk.minSdk.isPresent) return

        val components = project.extensions
            .findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
        if (components == null) {
            project.logger.warn(
                "[kmpSsot] ${project.path} applies com.android.kotlin.multiplatform.library but its " +
                        "components extension was not found — compileSdk/minSdk not propagated."
            )
            return
        }

        components.finalizeDsl { dsl ->
            if (sdk.compileSdk.isPresent) dsl.compileSdk = sdk.compileSdk.get()
            if (sdk.minSdk.isPresent) dsl.minSdk = sdk.minSdk.get()
        }

        if (sdk.targetSdk.isPresent || sdk.ndkVersion.isPresent) {
            project.logger.info(
                "[kmpSsot] ${project.path}: targetSdk/ndkVersion ignored — the KMP Android library " +
                        "DSL exposes neither."
            )
        }
    }
}
