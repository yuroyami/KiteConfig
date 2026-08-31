package io.github.yuroyami.kiteconfig

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Aligns Kotlin's `jvmTarget` with `kiteConfig { javaVersion }` so Kotlin and Java
 * agree, otherwise a module that sets only the Java compat gets the
 * "Inconsistent JVM-target compatibility (Java N vs Kotlin M)" error this knob is
 * meant to eliminate.
 *
 * Isolated in its own class (like [KmpAndroidLibraryWiring]) so the KGP task type
 * used in the `configureEach` lambda never enters a caller's method descriptor.
 * Only invoked behind [KiteConfigPlugin.KGP_ON_CLASSPATH], so the KGP class is always
 * loadable when this runs.
 */
internal object KotlinJvmTargetWiring {

    /** [javaVersionString] is the `JavaVersion.toString()` form: "1.8", "17", "21". */
    fun apply(project: Project, javaVersionString: String) {
        val target = JvmTarget.fromTarget(javaVersionString)
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(target)
        }
    }
}
