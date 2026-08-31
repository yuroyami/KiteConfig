package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Configuration-order guarantees for the read-back accessor.
 *
 * The root model is frozen in the root's `afterEvaluate`, which runs before any
 * subproject build script, so a subproject read sees settled values. Two values
 * resolve later than that and are the interesting cases:
 * `resolvedSharedProjectPath` can fall back to detection at `projectsEvaluated`,
 * and the locale list is finalized on first read.
 */
class ReadBackTimingTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, text: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(text.trimIndent())
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    private fun twoProjectFixture(rootExtras: String, appBody: String) {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                id.set("com.example.app")
                version.set("1.4.0")
                $rootExtras
            }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            $appBody
        """)
    }

    @Test
    fun `a subproject reading eagerly at configuration time sees the frozen root model`() {
        twoProjectFixture(
            rootExtras = "",
            appBody = """
                // Deliberately eager: runs while :app is being configured.
                val code = kiteConfig.versionCode.get()
                val appId = kiteConfig.androidApplicationId.get()
                tasks.register("readBack") {
                    doLast {
                        println("EAGER_CODE=" + code)
                        println("EAGER_APPID=" + appId)
                    }
                }
            """,
        )

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("EAGER_CODE=1001004000"), result.output)
        assertTrue(result.output.contains("EAGER_APPID=com.example.app"), result.output)
    }

    @Test
    fun `a provider wired into a task without get resolves at execution time`() {
        twoProjectFixture(
            rootExtras = "",
            appBody = """
                // Lazy: never forced during configuration.
                val code = kiteConfig.versionCode
                tasks.register("readBack") {
                    doLast { println("LAZY_CODE=" + code.get()) }
                }
            """,
        )

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("LAZY_CODE=1001004000"), result.output)
    }

    @Test
    fun `an explicit shared module path is readable eagerly from a subproject`() {
        twoProjectFixture(
            rootExtras = """modules { shared.set(":app") }""",
            appBody = """
                val shared = kiteConfig.resolvedSharedProjectPath.get()
                tasks.register("readBack") {
                    doLast { println("SHARED=" + shared) }
                }
            """,
        )

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("SHARED=:app"), result.output)
    }

    /**
     * Documents the one ordering trap: with no explicit `modules { shared }`, the
     * value can only come from the KMP census at `projectsEvaluated`, which runs
     * after every subproject script. An eager read cannot see it.
     */
    @Test
    fun `an undeclared shared module path is not resolvable eagerly from a subproject`() {
        twoProjectFixture(
            rootExtras = "",
            appBody = """
                val shared = kiteConfig.resolvedSharedProjectPath
                tasks.register("readBack") {
                    doLast { println("PRESENT_AT_EXEC=" + shared.isPresent) }
                }
                println("PRESENT_AT_CONFIG=" + shared.isPresent)
            """,
        )

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("PRESENT_AT_CONFIG=false"), result.output)
    }

    /** A real KMP module, so sole-project detection has something to find. */
    private fun kmpLocaleFixture(appBody: String) {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
            include(":shared")
        """)
        write("build.gradle.kts", """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kiteconfig")
            }
            kiteConfig {
                id.set("com.example.app")
                version.set("1.4.0")
                dryRun = true
            }
        """)
        write("shared/build.gradle.kts", """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm() }
        """)
        write("shared/src/commonMain/composeResources/values/strings.xml", "<resources/>")
        write("shared/src/commonMain/composeResources/values-fr/strings.xml", "<resources/>")
        write("shared/src/commonMain/composeResources/values-de/strings.xml", "<resources/>")
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            $appBody
        """)
    }

    /** Baseline: with no eager read, detection at projectsEvaluated supplies the list. */
    @Test
    fun `locales detected from the sole KMP module are visible at execution time`() {
        kmpLocaleFixture("""
            val locales = kiteConfig.canonicalLocales
            tasks.register("readBack") {
                doLast { println("LOCALES=" + locales.get()) }
            }
        """)

        val result = runner(":app:readBack").build()
        val found = Regex("LOCALES=\\[(.*?)]").find(result.output)?.groupValues?.get(1)

        assertTrue(!found.isNullOrBlank()) {
            "detection produced no locales, so the eager-read comparison would be vacuous: $result"
        }
        assertTrue(found!!.contains("fr") && found.contains("de"), result.output)
    }

    /**
     * Regression guard. The locale list is finalized at `projectsEvaluated`, once
     * detection can answer, rather than on first read. A build script that reads
     * it earlier gets an empty list for its own local copy, but must not change
     * what the rest of the build uses.
     *
     * Before the fix this returned an empty list to everyone; the baseline above
     * proves the same fixture detects `fr` and `de`.
     */
    @Test
    fun `an eager locale read does not change the list the build uses`() {
        kmpLocaleFixture("""
            val eager = kiteConfig.canonicalLocales.get()
            val later = kiteConfig.canonicalLocales
            tasks.register("readBack") {
                doLast {
                    println("EAGER=" + eager)
                    println("LATER=" + later.get())
                }
            }
        """)

        val result = runner(":app:readBack").build()
        val later = Regex("LATER=\\[(.*?)]").find(result.output)?.groupValues?.get(1)

        // The baseline proves detection yields fr and de for this exact fixture.
        // Reading eagerly must not change what the build ends up using.
        val eager = Regex("EAGER=\\[(.*?)]").find(result.output)?.groupValues?.get(1)

        assertTrue(later != null && eager != null, result.output)
        assertTrue(later!!.contains("fr") && later.contains("de")) {
            "an eager read changed the build's locales to \"$later\"; expected fr and de"
        }
        // The documented contract: an early reader sees an empty list locally,
        // because detection has not run yet. Pin it so the KDoc cannot drift.
        assertTrue(eager!!.isBlank()) {
            "expected the eager read to see an empty list, saw \"$eager\""
        }
    }
}
