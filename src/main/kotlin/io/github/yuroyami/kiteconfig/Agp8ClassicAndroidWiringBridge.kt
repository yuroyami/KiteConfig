package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.lang.reflect.InvocationTargetException
import java.util.function.Supplier

/** Loads the floor-compiled adapter without linking AGP-8-only descriptors here. */
internal object Agp8ClassicAndroidWiringBridge {
    private val adapterClass by lazy {
        try {
            Class.forName(
                "io.github.yuroyami.kiteconfig.Agp8ClassicAndroidWiring",
                true,
                KiteConfigPlugin::class.java.classLoader,
            )
        } catch (failure: ReflectiveOperationException) {
            throw GradleException(
                "[KITECONFIG-COMPAT-006] The packaged AGP 8 adapter could not be loaded; " +
                    "Android values were not applied.",
                failure,
            )
        }
    }

    fun wireApplication(project: Project, ext: KiteConfigExtension, resilient: Boolean) =
        invoke("wireApplication", project, ext, resilient)

    fun wireLibrary(project: Project, ext: KiteConfigExtension, resilient: Boolean) =
        invoke("wireLibrary", project, ext, resilient)

    private fun invoke(methodName: String, project: Project, ext: KiteConfigExtension, resilient: Boolean) {
        // Resolved lazily: the adapter calls this from inside finalizeDsl, so the
        // model is read at the same point the AGP 9 adapter reads it.
        val inputs = Supplier { ext.resolveAgp8Inputs(project, resilient) }
        try {
            val method = adapterClass.getDeclaredMethod(
                methodName,
                Project::class.java,
                Supplier::class.java,
            )
            if (!method.trySetAccessible()) {
                throw ReflectiveOperationException("AGP 8 adapter method is not accessible")
            }
            method.invoke(null, project, inputs)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw GradleException(
                "[KITECONFIG-COMPAT-006] The AGP 8 adapter failed while wiring ${project.path}.",
                cause,
            )
        } catch (failure: ReflectiveOperationException) {
            throw GradleException(
                "[KITECONFIG-COMPAT-006] The packaged AGP 8 adapter contract is incomplete; " +
                    "Android values were not applied to ${project.path}.",
                failure,
            )
        }
    }
}
