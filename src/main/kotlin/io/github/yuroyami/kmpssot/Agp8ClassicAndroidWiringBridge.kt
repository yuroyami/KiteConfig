package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.lang.reflect.InvocationTargetException

/** Loads the floor-compiled adapter without linking AGP-8-only descriptors here. */
internal object Agp8ClassicAndroidWiringBridge {
    private val adapterClass by lazy {
        try {
            Class.forName(
                "io.github.yuroyami.kmpssot.Agp8ClassicAndroidWiring",
                true,
                KmpSsotPlugin::class.java.classLoader,
            )
        } catch (failure: ReflectiveOperationException) {
            throw GradleException(
                "[KMPSSOT-COMPAT-006] The packaged AGP 8 adapter could not be loaded; " +
                    "Android values were not applied.",
                failure,
            )
        }
    }

    fun wireApplication(project: Project, ext: KmpSsotExtension) =
        invoke("wireApplication", project, ext)

    fun wireLibrary(project: Project, ext: KmpSsotExtension) =
        invoke("wireLibrary", project, ext)

    private fun invoke(methodName: String, project: Project, ext: KmpSsotExtension) {
        try {
            val method = adapterClass.getDeclaredMethod(
                methodName,
                Project::class.java,
                KmpSsotExtension::class.java,
            )
            if (!method.trySetAccessible()) {
                throw ReflectiveOperationException("AGP 8 adapter method is not accessible")
            }
            method.invoke(null, project, ext)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw GradleException(
                "[KMPSSOT-COMPAT-006] The AGP 8 adapter failed while wiring ${project.path}.",
                cause,
            )
        } catch (failure: ReflectiveOperationException) {
            throw GradleException(
                "[KMPSSOT-COMPAT-006] The packaged AGP 8 adapter contract is incomplete; " +
                    "Android values were not applied to ${project.path}.",
                failure,
            )
        }
    }
}
