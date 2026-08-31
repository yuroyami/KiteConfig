package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The accessor is the whole point of the read-back API: a submodule must reach
 * resolved values without the deprecated cross-project model.
 */
class ReadBackAccessorTest {

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

    @Test
    fun `a submodule reads the resolved version code with no deprecation warning`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                id.set("com.example.app")
                version.set("1.4.0")
            }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                val code = kiteConfig.versionCode
                val appId = kiteConfig.androidApplicationId
                doLast {
                    println("CODE=" + code.get())
                    println("APPID=" + appId.get())
                }
            }
        """)

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("CODE=1001004000"), result.output)
        assertTrue(result.output.contains("APPID=com.example.app"), result.output)
        assertFalse(result.output.contains("deprecat", ignoreCase = true), result.output)
    }

    @Test
    fun `reading an undeclared value fails the build instead of defaulting`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig { id.set("com.example.app") }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                val name = kiteConfig.appName
                doLast { println("NAME=" + name.get()) }
            }
        """)

        val result = runner(":app:readBack").buildAndFail()

        assertTrue(result.output.contains("no value"), result.output)
        assertFalse(result.output.contains("NAME="), result.output)
    }

    @Test
    fun `the accessor explains itself when the plugin is missing from the root`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        // On the classpath so the import resolves, but never applied, which is
        // the exact case the accessor's error message exists for.
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") apply false }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                doLast { println(kiteConfig.versionCode.get()) }
            }
        """)

        val result = runner(":app:readBack").buildAndFail()

        assertTrue(result.output.contains("io.github.yuroyami.kiteconfig"), result.output)
        assertTrue(result.output.contains("root project"), result.output)
    }
}
