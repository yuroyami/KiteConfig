package io.github.yuroyami.kmpssot

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end coverage of the plugin's iOS file rewrites via GradleRunner. No AGP
 * or Kotlin Multiplatform plugin is applied, so these run without an Android SDK
 * or Xcode — they exercise the root plugin + the iOS tasks against fixture files.
 */
class KmpSsotPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, content: String) {
        val f = File(projectDir, path)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    @Test
    fun `sanitize and sync rewrite the iOS pbxproj and Info_plist`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo App"
                versionName = "1.2.3"
                bundleIdBase = "com.demo.app"
                sharedModule = "shared"
                propagateLogo = false
            }
            """.trimIndent(),
        )
        write(
            "iosApp/iosApp.xcodeproj/project.pbxproj",
            """
            MARKETING_VERSION = 0.0.1;
            CURRENT_PROJECT_VERSION = 1;
            PRODUCT_NAME = Old;
            PRODUCT_BUNDLE_IDENTIFIER = com.old;
            """.trimIndent(),
        )
        write(
            "iosApp/iosApp/Info.plist",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleExecutable</key>
              <string>App</string>
            </dict>
            </plist>
            """.trimIndent(),
        )

        run("sanitizeIosProject", "syncIosConfig")

        val pbx = File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(pbx.contains("MARKETING_VERSION = 1.2.3;"), pbx)
        assertTrue(pbx.contains("CURRENT_PROJECT_VERSION = 1001002003;"), pbx)
        assertTrue(pbx.contains("PRODUCT_NAME = \"Demo App\";"), pbx)
        assertTrue(pbx.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app;"), pbx)

        val plistText = File(projectDir, "iosApp/iosApp/Info.plist").readText()
        assertTrue(plistText.contains("CFBundleName"), plistText)
        assertTrue(plistText.contains("\$(PRODUCT_NAME)"), plistText)
    }

    @Test
    fun `web generateIoWorker wires and generates in a real KMP js module`() {
        // Regression for the plugins.withId-vs-kotlin{} ordering bug: the worker
        // wiring must see targets declared AFTER the plugins block, i.e. it must
        // run at afterEvaluate, not at KMP-plugin-apply time.
        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories { mavenCentral(); gradlePluginPortal(); google() }
            }
            dependencyResolutionManagement {
                repositories { mavenCentral(); google() }
            }
            rootProject.name = "fixture"
            include(":shared")
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                sharedModule = "shared"
                web { generateIoWorker = true; ioWorkerPackage = "com.acme.gen" }
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle.kts",
            """
            // No version: KGP resolves from TestKit's injected plugin classpath, the
            // same classloader as the plugin under test (see pluginUnderTestMetadata).
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin {
                js { nodejs() }
            }
            """.trimIndent(),
        )

        run(":shared:generateKmpSsotIoWorkerJs")

        val generated = File(projectDir, "shared/build/generated/kmpssot/jsMain/kotlin/com/acme/gen/KmpSsotIoWorker.kt")
        assertTrue(generated.exists(), "generated worker file missing: ${generated.path}")
        val src = generated.readText()
        assertTrue(src.contains("package com.acme.gen"), src)
        assertTrue(src.contains("suspend fun kmpSsotOffload"), src)

        // Changing ioWorkerPackage must not leave the old file behind — two
        // top-level kmpSsotOffload declarations in jsMain would be a compile
        // error. The task wipes its output dir before regenerating.
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                sharedModule = "shared"
                web { generateIoWorker = true; ioWorkerPackage = "com.acme.other" }
            }
            """.trimIndent(),
        )
        run(":shared:generateKmpSsotIoWorkerJs")

        val moved = File(projectDir, "shared/build/generated/kmpssot/jsMain/kotlin/com/acme/other/KmpSsotIoWorker.kt")
        assertTrue(moved.exists(), "worker not regenerated at new package: ${moved.path}")
        assertTrue(!generated.exists(), "stale worker at old package survived a package change: ${generated.path}")
    }

    @Test
    fun `versionCodeOverride without versionName still writes the iOS build number`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                sharedModule = "shared"
                versionCodeOverride = 42
                propagateLogo = false
            }
            """.trimIndent(),
        )
        write(
            "iosApp/iosApp.xcodeproj/project.pbxproj",
            """
            MARKETING_VERSION = 0.0.1;
            CURRENT_PROJECT_VERSION = 1;
            """.trimIndent(),
        )

        run("syncIosConfig")

        val pbx = File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(pbx.contains("CURRENT_PROJECT_VERSION = 42;"), pbx)
        assertTrue(pbx.contains("MARKETING_VERSION = 0.0.1;"), pbx) // no versionName → untouched
    }

    @Test
    fun `verify task prints resolved values`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot { sharedModule = "shared"; versionName = "1.0.0"; appName = "X" }
            """.trimIndent(),
        )

        val result = run("kmpSsotVerify")
        assertTrue(result.output.contains("Resolved single source of truth"), result.output)
    }
}
