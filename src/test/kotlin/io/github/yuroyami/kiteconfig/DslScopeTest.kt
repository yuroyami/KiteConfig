package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Kotlin compiler is the assertion here.
 *
 * A nested block used to reach the one around it, so `android { skip(ios) }`
 * called the root's skip and excluded iOS everywhere, and a version corner
 * nested in another corner configured its sibling. Indentation said one thing
 * and the program did another.
 *
 * Every refusal is paired with the documented form it must not break. A marker
 * that quietly invalidates the real surface would be worse than no marker.
 */
class DslScopeTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, text: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun script(body: String) {
        write("settings.gradle.kts", "rootProject.name = \"scope-fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
            $body
            }
            """.trimIndent(),
        )
    }

    private fun runner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("help", "--stacktrace")

    private fun refuses(body: String, because: String) {
        script(body)
        val failure = runner().buildAndFail()
        assertTrue(
            failure.output.contains("Unresolved reference") ||
                failure.output.contains("can't be called in this context") ||
                failure.output.contains("Script compilation error"),
            "expected the compiler to refuse $because:\n${failure.output}",
        )
    }

    private fun accepts(body: String, because: String) {
        script(body)
        val result = runner().build()
        assertTrue(result.output.isNotEmpty(), "expected $because to configure: ${result.output}")
    }

    @Test
    fun `a platform block cannot call the root selection`() {
        refuses("    android { skip(ios) }", "a platform block reaching the root's skip")
    }

    @Test
    fun `the root selection still works where it belongs`() {
        accepts("    skip(desktop)", "root skip")
    }

    /** The remedy the old error messages printed did not compile. */
    @Test
    fun `the platform enum is accepted by the root selection`() {
        accepts(
            "    skip(io.github.yuroyami.kiteconfig.KitePlatform.DESKTOP)",
            "skip with the platform enum",
        )
    }

    @Test
    fun `iOS has no JVM settings`() {
        refuses("    ios { jvm { target = 17 } }", "a JVM block on iOS")
    }

    @Test
    fun `Android and desktop do have JVM settings`() {
        accepts(
            """
            |    jvm { target = 17 }
            |    android { jvm { target = 17 } }
            |    desktop { jvm { target = 17 } }
            """.trimMargin(),
            "the JVM blocks that exist",
        )
    }

    @Test
    fun `the resource filter exists only on Android`() {
        refuses("    ios { locales { filterResources = true } }", "the Android resource filter on iOS")
    }

    @Test
    fun `the splash theme exists only on Android`() {
        refuses("    desktop { splash { theme = \"AppTheme\" } }", "the Android splash theme on desktop")
    }

    @Test
    fun `the documented platform overrides all compile`() {
        accepts(
            """
            |    appName = "Jetzy"
            |    appId = "com.example.jetzy"
            |    version = "1.4.0"
            |    locales { tags = listOf("en") }
            |    android {
            |        appId { suffix = ".android" }
            |        version { rebuild = 1; publishedBuildNumber = "1001003999" }
            |        locales { filterResources = true }
            |        logo { foregroundScale = 0.61 }
            |        splash { theme = "AppTheme" }
            |    }
            |    ios {
            |        appName = "Jetzy Lite"
            |        appId = "com.example.jetzy.ios"
            |        version = "1.5.0"
            |        splash { enabled = false }
            |    }
            |    desktop {
            |        logo { roundMac = true }
            |        linuxPackageName = "jetzy"
            |    }
            """.trimMargin(),
            "the documented surface",
        )
    }
}
