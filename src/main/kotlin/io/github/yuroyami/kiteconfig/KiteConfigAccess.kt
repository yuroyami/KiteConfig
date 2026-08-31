package io.github.yuroyami.kiteconfig

import org.gradle.api.Project
import org.gradle.api.GradleException

/**
 * Deprecated compatibility access to the root [KiteConfigExtension].
 *
 * Use this accessor only for reads. The returned extension still has mutable
 * Gradle property types because it is the root DSL model.
 *
 * Prefer reading values in the root `build.gradle.kts`, where the KiteConfig plugin
 * is applied. Read the root extension directly and keep values as providers when
 * the receiving API supports them:
 *
 * ```kotlin
 * import io.github.yuroyami.kiteconfig.KiteConfigExtension
 * import org.gradle.kotlin.dsl.getByType
 *
 * val ssot = extensions.getByType<KiteConfigExtension>()
 * val minSdkProvider = ssot.android.minSdk
 * anotherExtension.minimumSdk.set(minSdkProvider)
 * ```
 *
 * This SDK value is optional. Use `orNull` when an unset value is valid. Call
 * `get()` only when the value is required and an eager value is unavoidable:
 *
 * ```kotlin
 * val optionalMinSdk: Int? = ssot.android.minSdk.orNull
 * val requiredMinSdk: Int = ssot.android.minSdk.get()
 * ```
 *
 * `get()` fails when the optional value is unset.
 *
 * Old subproject build logic can still read the root model through this accessor:
 *
 * ```kotlin
 * import io.github.yuroyami.kiteconfig.kiteConfig
 *
 * val minSdkProvider = kiteConfig.android.minSdk
 * ```
 *
 * Treat cross-project access as read-only. It is deprecated and is not compatible
 * with Gradle Isolated Projects. KiteConfig freezes the model after root-project
 * evaluation, so late writes fail. New module build logic should use its local
 * platform DSL or a generated read-only output instead.
 */
@Deprecated(
    message = "Cross-project mutable model access is not isolated-project safe; configure kiteConfig only in the root DSL.",
    level = DeprecationLevel.WARNING,
)
val Project.kiteConfig: KiteConfigExtension
    get() = rootProject.extensions.findByType(KiteConfigExtension::class.java)
        ?: throw GradleException(
            "kiteConfig model is unavailable: apply io.github.yuroyami.kiteconfig to the root project before using the compatibility accessor."
        )
