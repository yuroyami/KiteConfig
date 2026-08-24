package io.github.yuroyami.kitessot

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Isolated wiring for AGP's `com.android.kotlin.multiplatform.library` plugin: the Android
 * target of a Kotlin Multiplatform module declared via `kotlin { androidLibrary { } }`
 * (or `android { }` under the AGP 9 `android.newDsl`).
 *
 * Kept in its **own class** on purpose. Its DSL type lives in AGP (a `compileOnly`
 * dependency), and referencing it inside a `finalizeDsl { }` lambda puts the type into a
 * method descriptor. Were that on [KiteSsotPlugin] itself, Gradle would fail to *decorate*
 * the plugin whenever AGP is absent from the classpath (e.g. the iOS-only functional tests).
 * By living here, the AGP type is only class-loaded when the KMP library plugin is actually
 * applied (which is exactly when AGP is present), so the core plugin stays AGP-free.
 *
 * Why [KotlinMultiplatformAndroidComponentsExtension.finalizeDsl] rather than mutating the
 * extension directly: unlike the classic `com.android.application`/`com.android.library`
 * plugins, this plugin does **not** create its DSL extension synchronously at apply time, so
 * reading it inside the `withId` callback finds nothing. `finalizeDsl` is AGP's sanctioned
 * hook that runs after the module's own DSL block but before variants lock, so the SSOT
 * value wins over a module-local `compileSdk` — and is supplied outright when the module
 * declares none. It also runs on the diagnostic invocations, through [wireValueGroup], for
 * the same reason as [ClassicAndroidWiring]: AGP validates its DSL on every invocation.
 * [SsotDriftLog] flags module declarations the SSOT replaces.
 *
 * Only `compileSdk`/`minSdk` apply: the KMP library DSL has no `targetSdk` (libraries never
 * did) nor `ndkVersion`, so those are skipped even when set. Locale propagation also doesn't
 * apply here. The application module owns the locale list.
 */
internal object KmpAndroidLibraryWiring {

    /** Returns false when the expected public components extension is absent. */
    fun apply(project: Project, ext: KiteSsotExtension, resilient: Boolean): Boolean {
        val components = project.extensions
            .findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
        if (components == null) {
            return false
        }

        components.finalizeDsl { dsl ->
            val drift = SsotDriftLog(project)
            project.wireValueGroup(resilient, "the SDK levels") {
                if (ext.effectiveApplySdkLevels.get()) {
                    val sdk = ext.android
                    if (sdk.compileSdk.isPresent) {
                        val applied = sdk.compileSdk.get()
                        drift.observe("compileSdk", dsl.compileSdk, applied)
                        dsl.compileSdk = applied
                    }
                    if (sdk.minSdk.isPresent) {
                        val applied = sdk.minSdk.get()
                        drift.observe("minSdk", dsl.minSdk, applied)
                        dsl.minSdk = applied
                    }
                    if (sdk.targetSdk.isPresent || sdk.ndk.isPresent) {
                        project.logger.info(
                            "[kiteSsot] ${project.path}: targetSdk/ndkVersion ignored: the KMP Android library " +
                                "DSL exposes neither."
                        )
                    }
                }
            }
            drift.report()
        }
        return true
    }
}
