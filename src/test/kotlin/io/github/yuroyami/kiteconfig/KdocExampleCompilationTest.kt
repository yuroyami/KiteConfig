package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Every `kiteConfig { }` example in the public KDoc is executed against a real
 * Gradle build.
 *
 * A fenced block in a doc comment is just a string: nothing type-checks it, so a
 * renamed property leaves behind advice that looks right and is not. This ran
 * once already against the README and found three real errors, one of them a
 * genuine plugin bug. Executing them is the only thing that keeps them honest.
 */
class KdocExampleCompilationTest {

    private data class Example(val source: String, val index: Int, val code: String)

    /** Pull every complete `kiteConfig { }` fence out of the main source's KDoc. */
    private fun documentedExamples(): List<Example> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "expected the project root, got ${File(".").absolutePath}")
        val found = mutableListOf<Example>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.name }
            .forEach { file ->
                var inside = false
                val buffer = mutableListOf<String>()
                file.readLines().forEach { raw ->
                    val line = raw.trim().let { if (it.startsWith("*")) it.removePrefix("*").trim() else it }
                    when {
                        !inside && line.startsWith("```kotlin") -> { inside = true; buffer.clear() }
                        inside && line.startsWith("```") -> {
                            inside = false
                            val code = buffer.joinToString("\n")
                            if (code.trimStart().startsWith("kiteConfig {")) {
                                found += Example(file.name, found.size + 1, code)
                            }
                        }
                        inside -> buffer += line
                    }
                }
            }
        return found
    }

    @Test
    fun `every documented kiteConfig example configures a real build`(@TempDir projectDir: File) {
        val examples = documentedExamples()
        assertTrue(examples.size >= 10, "expected the KDoc examples to be found, got ${examples.size}")

        // A fixture rich enough for the examples to mean something: a KMP shared
        // module, an Android application module, and a logo source file.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { mavenCentral(); gradlePluginPortal(); google() }
            }
            dependencyResolutionManagement {
                repositories { mavenCentral(); google() }
            }
            rootProject.name = "kdoc-examples"
            include(":shared")
            """.trimIndent(),
        )
        File(projectDir, "shared").mkdirs()
        File(projectDir, "shared/build.gradle.kts").writeText(
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm(); js { browser() } }
            """.trimIndent(),
        )
        File(projectDir, "art").mkdirs()
        ImageIO.write(
            BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB),
            "PNG",
            File(projectDir, "art/logo_fg.png"),
        )

        val failures = mutableListOf<String>()
        examples.forEach { example ->
            File(projectDir, "build.gradle.kts").writeText(
                """
                import io.github.yuroyami.kiteconfig.PlistConflictPolicy
                plugins {
                    id("org.jetbrains.kotlin.multiplatform") apply false
                    id("io.github.yuroyami.kiteconfig")
                }
                ${example.code}
                """.trimIndent(),
            )
            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("help", "--stacktrace")
                .forwardOutput()
                .buildAndFail_orSuccess()
            if (!result.first) {
                failures += "${example.source} example #${example.index} does not configure:\n" +
                    result.second.lines().filter { it.isNotBlank() }.take(6).joinToString("\n")
            }
        }
        assertTrue(failures.isEmpty(), "\n\n" + failures.joinToString("\n\n"))
    }

    /** Run the build, reporting success plus the output either way. */
    private fun GradleRunner.buildAndFail_orSuccess(): Pair<Boolean, String> = try {
        true to build().output
    } catch (failure: Exception) {
        false to (failure.message ?: "unknown failure")
    }
}
