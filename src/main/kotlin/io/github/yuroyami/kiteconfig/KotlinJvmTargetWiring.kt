package io.github.yuroyami.kiteconfig

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Applies `kiteConfig { jvm { } }` to one project.
 *
 * The bytecode level goes to **both** compilers. Setting only Kotlin's is what
 * used to break any module holding Java sources as well: Java kept compiling to
 * the toolchain's own level and the build died on "Inconsistent JVM-target
 * compatibility", which is the error this setting exists to prevent.
 *
 * Isolated in its own class (like [KmpAndroidLibraryWiring]) so the KGP task
 * type used in the `configureEach` lambda never enters a caller's method
 * descriptor. Only invoked behind [KiteConfigPlugin.KGP_ON_CLASSPATH], so the
 * KGP class is always loadable when this runs.
 */
internal object KotlinJvmTargetWiring {

    /**
     * [javaVersionString] is the `JavaVersion.toString()` form: "1.8", "17", "21".
     *
     * Android modules pass `alignJava = false`: AGP owns their Java settings
     * through `compileOptions`, which the Android wiring writes instead.
     */
    fun apply(project: Project, javaVersionString: String, alignJava: Boolean = true) {
        val target = JvmTarget.fromTarget(javaVersionString)
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(target)
        }
        if (!alignJava) return

        val java = JavaVersion.toVersion(javaVersionString).toString()
        project.tasks.withType(JavaCompile::class.java).configureEach {
            sourceCompatibility = java
            targetCompatibility = java
        }
    }

    /**
     * Selects the JDK that compiles this project and runs its host tests.
     *
     * Applied through the Java extension, which Kotlin's own toolchain support
     * reads as well, so one call covers both compilers. A module that already
     * pinned a different toolchain keeps it: `languageVersion` is a property
     * with a final value once set, and overwriting it is not this plugin's call.
     */
    fun applyToolchain(project: Project, majorVersion: Int) {
        val java = project.extensions.findByType(JavaPluginExtension::class.java) ?: return
        val requested = JavaLanguageVersion.of(validateJavaVersion(majorVersion))
        runCatching { java.toolchain.languageVersion.set(requested) }
            .onFailure {
                project.logger.info(
                    "[kiteConfig] ${project.path} keeps its own Java toolchain; " +
                        "kiteConfig { jvm { toolchain } } was not applied here.",
                )
            }
    }
}
