package io.github.yuroyami.kiteconfig

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdeSyncFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, text: String) {
        projectDir.resolve(path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
    }

    private fun runImport() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(":shared:prepareKotlinIdeaImport", ":other:prepareKotlinIdeaImport",
            "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")
        .build()

    @Test
    fun `real Kotlin IDE import syncs only selected Xcode targets and reuses configuration cache`() {
        write("settings.gradle.kts", """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "ide-sync"
            include(":shared", ":other")
        """)
        write("build.gradle.kts", """
            plugins {
                id("io.github.yuroyami.kiteconfig")
                kotlin("multiplatform") apply false
            }
            kiteConfig {
                version = "4.0.2"
                version { rebuild = 1 }
                autoApply = true
                only(ios)
                modules { shared = ":shared" }
                ios { xcodeTargets("Phone") }
            }
        """)
        write("shared/build.gradle.kts", """
            plugins { kotlin("multiplatform") }
            kotlin { jvm() }
        """)
        write("other/build.gradle.kts", """tasks.register("prepareKotlinIdeaImport")""")
        val pbx = projectDir.resolve("iosApp/iosApp.xcodeproj/project.pbxproj")
        write(pbx.relativeTo(projectDir).path, pbxProject("Phone", "Other"))
        write("iosApp/iosApp/Info.plist", """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>CFBundleShortVersionString</key><string>$(MARKETING_VERSION)</string>
            <key>CFBundleVersion</key><string>$(CURRENT_PROJECT_VERSION)</string>
            </dict></plist>
        """)

        val first = runImport()
        assertEquals(1, first.tasks.count { it.path == ":kiteApplyIos" })
        val synced = pbx.readText()
        assertTrue(synced.contains("MARKETING_VERSION = 4.0.2;"), synced)
        assertTrue(synced.contains("CURRENT_PROJECT_VERSION = 1004000021;"), synced)
        assertTrue(synced.contains("MARKETING_VERSION = 0.0.1;"), "unselected target was changed")
        assertTrue(synced.contains("CURRENT_PROJECT_VERSION = 1;"), "unselected target was changed")

        val second = runImport()
        assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
        assertEquals(synced, pbx.readText(), "repeat IDE sync must be idempotent")

        val build = projectDir.resolve("build.gradle.kts")
        build.writeText(build.readText().replace("4.0.2", "4.0.3"))
        runImport()
        assertTrue(pbx.readText().contains("MARKETING_VERSION = 4.0.3;"))
        assertTrue(pbx.readText().contains("CURRENT_PROJECT_VERSION = 1004000031;"))
    }

    private fun pbxProject(vararg applicationTargets: String): String {
        require(applicationTargets.size in 1..2)
        data class Ids(val target: String, val list: String, val config: String)
        val ids = listOf(
            Ids(
                "AA00000000000000000000AA",
                "AA00000000000000000000A1",
                "AA00000000000000000000A2",
            ),
            Ids(
                "DD00000000000000000000DD",
                "DD00000000000000000000D1",
                "DD00000000000000000000D2",
            ),
        )
        val targets = applicationTargets.mapIndexed { index, name ->
            val id = ids[index]
            """
                ${id.target} = {
                    isa = PBXNativeTarget;
                    name = "$name";
                    buildConfigurationList = ${id.list};
                    productType = "com.apple.product-type.application";
                };
            """.trimIndent()
        }.joinToString("\n")
        val configs = applicationTargets.mapIndexed { index, name ->
            val id = ids[index]
            """
                ${id.config} = {
                    isa = XCBuildConfiguration;
                    buildSettings = {
                        CURRENT_PROJECT_VERSION = 1;
                        MARKETING_VERSION = 0.0.1;
                        PRODUCT_BUNDLE_IDENTIFIER = com.example.app${index + 1};
                        PRODUCT_NAME = $name;
                    };
                    name = Debug;
                };
            """.trimIndent()
        }.joinToString("\n")
        val lists = applicationTargets.mapIndexed { index, _ ->
            val id = ids[index]
            """
                ${id.list} = {
                    isa = XCConfigurationList;
                    buildConfigurations = (
                        ${id.config},
                    );
                };
            """.trimIndent()
        }.joinToString("\n")
        return """
            // !${'$'}*UTF8*${'$'}!
            {
                objects = {
                    $targets
                    $configs
                    $lists
                    EE00000000000000000000EE = {
                        isa = PBXProject;
                        knownRegions = (
                            Base,
                            en,
                        );
                    };
                };
                rootObject = EE00000000000000000000EE;
            }
        """.trimIndent()
    }

}
