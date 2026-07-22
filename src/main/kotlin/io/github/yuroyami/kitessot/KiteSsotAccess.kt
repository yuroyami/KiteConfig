package io.github.yuroyami.kitessot

import org.gradle.api.Project
import org.gradle.api.GradleException

/**
 * Compatibility accessor for the root [KiteSsotExtension]. It is not
 * isolated-project compatible. The plugin validates and freezes the model at
 * the end of root-project evaluation, so late writes through this accessor fail.
 * New module build logic should use its local platform DSL or a generated/read-only
 * output instead of reaching into another project.
 *
 * Usage in a subproject build.gradle.kts:
 *
 *     import io.github.yuroyami.kitessot.kiteSsot
 *     val v = kiteSsot.versionName.get()
 */
@Deprecated(
    message = "Cross-project mutable model access is not isolated-project safe; configure kiteSsot only in the root DSL.",
    level = DeprecationLevel.WARNING,
)
val Project.kiteSsot: KiteSsotExtension
    get() = rootProject.extensions.findByType(KiteSsotExtension::class.java)
        ?: throw GradleException(
            "kiteSsot model is unavailable: apply io.github.yuroyami.kitessot to the root project before using the compatibility accessor."
        )
