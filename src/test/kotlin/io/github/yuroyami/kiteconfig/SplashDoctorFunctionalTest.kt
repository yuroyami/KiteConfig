package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Doctor coverage for the splash prerequisites: theme corner and manifest placeholder. */
class SplashDoctorFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, content: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    @Test
    fun `doctor flags a missing theme corner, then a missing manifest placeholder`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "androidApp/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>",
        )
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                modules { androidAppDirectory = file("androidApp") }
                logo { backgroundColor.set("#102A43") }
                splash { }
            }
            """.trimIndent(),
        )

        val missingTheme = run("kiteDoctor")
        assertTrue(missingTheme.output.contains("[FAIL] KTCNFG091"), missingTheme.output)

        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                modules { androidAppDirectory = file("androidApp") }
                logo { backgroundColor.set("#102A43") }
                splash { android { theme.set("AppTheme") } }
            }
            """.trimIndent(),
        )

        val missingPlaceholder = run("kiteDoctor")
        assertTrue(missingPlaceholder.output.contains("[FAIL] KTCNFG092"), missingPlaceholder.output)

        write(
            "androidApp/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<application android:theme=\"\${kiteSplashTheme}\"/></manifest>",
        )

        val healthy = run("kiteDoctor")
        assertTrue(healthy.output.contains("[PASS] KTCNFG090"), healthy.output)
    }

    @Test
    fun `armed splash without the xcode rewrite warns`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                logo { backgroundColor.set("#102A43") }
                splash {
                    skip(desktop)
                    android { theme.set("AppTheme") }
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val result = run("kiteDoctor")
        assertTrue(result.output.contains("KTCNFG093"), result.output)
    }
}
