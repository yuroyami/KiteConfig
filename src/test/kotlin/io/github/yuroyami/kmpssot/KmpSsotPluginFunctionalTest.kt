package io.github.yuroyami.kmpssot

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Consumer-build coverage for the plugin's public Gradle surface. The fixtures use
 * real pbxproj object graphs and real Kotlin Multiplatform compilations; no Android
 * SDK or Xcode invocation is required.
 *
 * KGP is intentionally applied without a version in the fixtures. The production
 * build adds it to `pluginUnderTestMetadata`, placing KGP in the same TestKit
 * classloader as kmp-ssot and exercising the documented root `apply false` setup.
 */
class KmpSsotPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, content: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun writePng(path: String, size: Int) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(image, "PNG", file)
    }

    private fun run(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .build()

    private fun runAndFail(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .buildAndFail()

    private fun settingsWithShared() = """
        pluginManagement {
            repositories { mavenCentral(); gradlePluginPortal(); google() }
        }
        dependencyResolutionManagement {
            repositories { mavenCentral(); google() }
        }
        rootProject.name = "fixture"
        include(":shared")
    """.trimIndent()

    /**
     * Minimal but structurally real pbxproj graph. Each application target owns one
     * configuration list and one XCBuildConfiguration containing every setting the
     * migration can require. A PBXProject object supplies a real knownRegions list.
     */
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

    private fun plist(body: String = "\t<key>CFBundleExecutable</key>\n\t<string>App</string>") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n<dict>\n$body\n</dict>\n</plist>"

    @Test
    fun `explicit sanitize and sync rewrite one selected iOS application`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo App"
                versionName = "1.2.3"
                iosMarketingVersion = "1.2.3"
                iosBuildNumber = "42"
                bundleIdBase = "com.demo.app"
                locales.addAll(listOf("fr", "pt-BR"))
                syncIos = true
                sanitizeIosProject = true
            }
            """.trimIndent(),
        )
        write("iosApp/iosApp.xcodeproj/project.pbxproj", pbxProject("Phone"))
        write("iosApp/iosApp/Info.plist", plist())

        // The config task plans plist + pbxproj changes as one transaction; both
        // operations are explicit opt-ins and neither is attached to compilation.
        run("kmpSsotSyncIosConfig")

        val pbx = File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(pbx.contains("MARKETING_VERSION = 1.2.3;"), pbx)
        assertTrue(pbx.contains("CURRENT_PROJECT_VERSION = 42;"), pbx)
        assertTrue(pbx.contains("PRODUCT_NAME = \"Demo App\";"), pbx)
        assertTrue(pbx.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app;"), pbx)
        assertTrue(pbx.contains("pt-BR"), pbx)

        val plistText = File(projectDir, "iosApp/iosApp/Info.plist").readText()
        assertTrue(plistText.contains("CFBundleName"), plistText)
        assertTrue(plistText.contains("\$(PRODUCT_NAME)"), plistText)
        assertTrue(plistText.contains("CFBundleVersion"), plistText)
        assertTrue(plistText.contains("\$(CURRENT_PROJECT_VERSION)"), plistText)
    }

    @Test
    fun `ambiguous pbxproj fails closed until an application target is selected`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        fun build(targetSelector: String) = """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo"
                versionName = "1.2.3"
                iosMarketingVersion = "1.2.3"
                iosBuildNumber = "7"
                bundleIdBase = "com.demo.app"
                syncIos = true
                $targetSelector
            }
        """.trimIndent()
        write("build.gradle.kts", build(""))
        val original = pbxProject("Phone", "TV")
        write("iosApp/iosApp.xcodeproj/project.pbxproj", original)

        val ambiguous = runAndFail("kmpSsotSyncIosConfig")
        assertTrue(ambiguous.output.contains("multiple application targets"), ambiguous.output)
        assertTrue(
            File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText() == original,
            "ambiguous migration changed the pbxproj",
        )

        write("build.gradle.kts", build("ios { targetNames.add(\"Phone\") }"))
        run("kmpSsotSyncIosConfig")
        val selected = File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(selected.contains("PRODUCT_NAME = \"Demo\";"), selected)
        assertTrue(selected.contains("PRODUCT_NAME = TV;"), selected)
        assertTrue(selected.contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.app2;"), selected)
    }

    @Test
    fun `configured missing pbxproj fails instead of reporting a successful sync`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo"
                versionName = "1.2.3"
                iosMarketingVersion = "1.2.3"
                iosBuildNumber = "7"
                syncIos = true
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kmpSsotSyncIosConfig")
        assertTrue(failure.output.contains("Configured pbxproj does not exist"), failure.output)
        assertTrue(failure.output.contains("project.pbxproj"), failure.output)
    }

    @Test
    fun `shared module migration requires and uses an explicit from and to`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                propagateVersion = false
                propagateAppName = false
                propagateBundleId = false
                propagateLocaleList = false
                propagateSharedModule = true
                iosPreviousSharedModuleName = "shared"
                iosSharedModuleName = "composeApp"
            }
            """.trimIndent(),
        )
        write("iosApp/iosApp.xcodeproj/project.pbxproj", pbxProject("Phone"))
        write("iosApp/Podfile", "pod 'Utils', :path => '../Utils'\npod 'shared', :path => '../shared'\n")
        write("iosApp/iosApp/ContentView.swift", "import Utils\nimport shared\n")

        run("kmpSsotSyncIosConfig")

        val podfile = File(projectDir, "iosApp/Podfile").readText()
        assertTrue(podfile.contains("pod 'Utils', :path => '../Utils'"), podfile)
        assertTrue(podfile.contains("pod 'composeApp', :path => '../composeApp'"), podfile)
        val swift = File(projectDir, "iosApp/iosApp/ContentView.swift").readText()
        assertTrue(swift.contains("import Utils"), swift)
        assertTrue(swift.contains("import composeApp"), swift)
        assertFalse(swift.contains("import shared"), swift)
    }

    @Test
    fun `over-depth Swift discovery aborts the whole iOS text transaction`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Migrated"
                syncIos = true
                propagateVersion = false
                propagateAppName = true
                propagateBundleId = false
                propagateLocaleList = false
                propagateSharedModule = true
                iosPreviousSharedModuleName = "shared"
                iosSharedModuleName = "composeApp"
            }
            """.trimIndent(),
        )
        val originalPbxproj = pbxProject("Phone")
        val originalSwift = "import shared\n"
        val pbxprojPath = "iosApp/iosApp.xcodeproj/project.pbxproj"
        val shallowSwiftPath = "iosApp/iosApp/ContentView.swift"
        val deepSwiftPath = (1..MAX_IOS_SWIFT_TRAVERSAL_DEPTH).joinToString(
            separator = "/",
            prefix = "iosApp/",
            postfix = "/TooDeep.swift",
        ) { level -> "level$level" }
        write(pbxprojPath, originalPbxproj)
        write(shallowSwiftPath, originalSwift)
        write(deepSwiftPath, originalSwift)

        val failure = runAndFail("kmpSsotSyncIosConfig")

        assertTrue(
            failure.output.contains("maximum depth $MAX_IOS_SWIFT_TRAVERSAL_DEPTH"),
            failure.output,
        )
        assertTrue(File(projectDir, pbxprojPath).readText() == originalPbxproj, "pbxproj was partially committed")
        assertTrue(File(projectDir, shallowSwiftPath).readText() == originalSwift, "Swift source was partially committed")
        assertFalse(File(projectDir, "$pbxprojPath.kmpssot.bak").exists(), "backup was created before planning finished")
    }

    @Test
    fun `iOS logo takeover creates an external backup and ownership manifest`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                propagateLogo = true
                ios { deploymentTarget = "12.0" }
                appLogoPngForeground = file("art/fg.png")
                appLogoBackgroundColor = "#80FF5500"
            }
            """.trimIndent(),
        )
        writePng("art/fg.png", 64)
        val appiconset = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
        write("$appiconset/Contents.json", "{ \"user-authored\" : true }")
        writePng("$appiconset/AppIcon-40.png", 40)

        val result = run("kmpSsotSyncIosLogo")

        val catalogIdentity = SyncIosLogoTask.catalogIdentity(projectDir, File(projectDir, appiconset))
        val recoveryRoot = File(projectDir, ".kmpssot/recovery/ios-appicon/$catalogIdentity")
        assertTrue(File(recoveryRoot, "removal-provenance.tsv").isFile, "backup provenance missing")
        val backedUpContents = recoveryRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "Contents.json" && it.readText().contains("user-authored") }
        assertTrue(backedUpContents != null, "user-owned Contents.json was not backed up before takeover")

        val ownership = File(
            projectDir,
            "iosApp/iosApp/.kmpssot/$catalogIdentity/owned-files-v1",
        )
        assertTrue(ownership.isFile, "iOS AppIcon ownership manifest missing")
        assertTrue(ownership.readText().contains("ios-appicon"), ownership.readText())
        assertTrue(result.output.contains("flattening over white because App Store icons must be opaque"), result.output)
        assertTrue(result.output.contains("no longer referenced"), result.output)
    }

    @Test
    fun `web worker is selected explicitly and generated source compiles for browser JS`() {
        write("settings.gradle.kts", settingsWithShared())
        fun rootBuild(workerPackage: String) = """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kmpssot")
            }
            kmpSsot {
                sharedProjectPath = ":shared"
                dryRun = true
                web {
                    generateIoWorker = true
                    projectPaths.add(":shared")
                    browserTargetNames.add("js")
                    ioWorkerPackage = "$workerPackage"
                }
            }
        """.trimIndent()
        write("build.gradle.kts", rootBuild("com.acme.gen"))
        write(
            "shared/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin {
                js { browser() }
                sourceSets {
                    jsMain.dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                    }
                }
            }
            """.trimIndent(),
        )
        write(
            "shared/src/jsMain/kotlin/WorkerConsumer.kt",
            """
            package fixture
            import com.acme.gen.kmpSsotOffload
            suspend fun workerProbe(): String =
                kmpSsotOffload("(value) => value", "ok", 1_000L)
            """.trimIndent(),
        )

        val compile = run(":shared:compileKotlinJs")
        assertTrue(compile.output.contains("BUILD SUCCESSFUL"), compile.output)

        val generated = File(
            projectDir,
            "shared/build/generated/kmpssot/jsMain/kotlin/com/acme/gen/KmpSsotIoWorker.kt",
        )
        assertTrue(generated.isFile, "generated worker file missing: ${generated.path}")
        assertTrue(generated.readText().contains("suspend fun kmpSsotOffload"), generated.readText())

        // A package move must delete only the previous manifest-owned source.
        write("build.gradle.kts", rootBuild("com.acme.other"))
        run(":shared:generateKmpSsotIoWorkerJs")
        val moved = File(
            projectDir,
            "shared/build/generated/kmpssot/jsMain/kotlin/com/acme/other/KmpSsotIoWorker.kt",
        )
        assertTrue(moved.isFile, "worker was not regenerated at its new package")
        assertFalse(generated.exists(), "stale manifest-owned worker survived a package move")
    }

    @Test
    fun `worker generation fails when no browser target selector is declared`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kmpssot")
            }
            kmpSsot {
                sharedProjectPath = ":shared"
                web {
                    generateIoWorker = true
                    projectPaths.add(":shared")
                }
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { js { nodejs() } }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("no browser target was selected"), failure.output)
    }

    @Test
    fun `BuildConfig has complete identity and generated source compiles`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kmpssot")
            }
            kmpSsot {
                sharedProjectPath = ":shared"
                appName = "Demo"
                versionName = "1.2.3"
                bundleIdBase = "com.acme.app"
                locales.addAll(listOf("en", "pt-BR"))
                dryRun = true
                buildConfig {
                    enabled = true
                    packageName = "com.acme.gen"
                    className = "AppConfig"
                    stringField("BASE_URL", "https://api.acme.com")
                    intField("API_TIMEOUT_MS", 30000)
                    intField("MIN_INT", Int.MIN_VALUE)
                    longField("MIN_LONG", Long.MIN_VALUE)
                    booleanField("ANALYTICS_ENABLED", true)
                }
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm() }
            """.trimIndent(),
        )
        write(
            "shared/src/commonMain/kotlin/ConfigConsumer.kt",
            """
            package fixture
            import com.acme.gen.AppConfig
            val configProbe: String = AppConfig.appName + AppConfig.BASE_URL + AppConfig.locales.first() +
                AppConfig.MIN_INT + AppConfig.MIN_LONG
            """.trimIndent(),
        )

        val compile = run(":shared:compileKotlinJvm")
        assertTrue(compile.output.contains("BUILD SUCCESSFUL"), compile.output)

        val generated = File(
            projectDir,
            "shared/build/generated/kmpssot/commonMain/kotlin/com/acme/gen/AppConfig.kt",
        )
        assertTrue(generated.isFile, "generated BuildConfig missing: ${generated.path}")
        val source = generated.readText()
        assertTrue(source.contains("public const val appName: String = \"Demo\""), source)
        assertTrue(source.contains("public const val versionName: String = \"1.2.3\""), source)
        assertTrue(source.contains("public const val versionCode: Int = 1001002003"), source)
        assertTrue(source.contains("public const val androidApplicationId: String = \"com.acme.app\""), source)
        assertTrue(source.contains("public const val iosBundleId: String = \"com.acme.app\""), source)
        assertTrue(source.contains("public val locales: List<String> = listOf(\"en\", \"pt-BR\")"), source)
        assertTrue(source.contains("public const val BASE_URL: String = \"https://api.acme.com\""), source)
        assertTrue(source.contains("public const val API_TIMEOUT_MS: Int = 30000"), source)
        assertTrue(source.contains("public const val MIN_INT: Int = Int.MIN_VALUE"), source)
        assertTrue(source.contains("public const val MIN_LONG: Long = Long.MIN_VALUE"), source)
        assertTrue(source.contains("public const val ANALYTICS_ENABLED: Boolean = true"), source)

        // Identity-only generation must not require the legacy/custom field
        // transport to have been touched by the consumer.
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kmpssot")
            }
            kmpSsot {
                sharedProjectPath = ":shared"
                appName = "Demo"
                versionName = "1.2.3"
                bundleIdBase = "com.acme.app"
                buildConfig {
                    enabled = true
                    packageName = "com.acme.gen"
                    className = "AppConfig"
                }
            }
            """.trimIndent(),
        )
        val identityOnly = run(":shared:generateKmpSsotBuildConfig")
        assertTrue(identityOnly.output.contains("BUILD SUCCESSFUL"), identityOnly.output)
        val identityOnlySource = generated.readText()
        assertTrue(identityOnlySource.contains("public const val appName"), identityOnlySource)
        assertFalse(identityOnlySource.contains("BASE_URL"), identityOnlySource)
    }

    @Test
    fun `fields-only BuildConfig never realizes identity task providers`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            import io.github.yuroyami.kmpssot.GenerateBuildConfigTask

            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kmpssot")
            }
            kmpSsot {
                sharedProjectPath = ":shared"
                buildConfig {
                    enabled = true
                    includeIdentity = false
                    packageName = "com.acme.fields"
                    className = "PublicConfig"
                    intField("ANSWER", 42)
                }
            }

            val unreadString = providers.provider<String> {
                error("fields-only generation realized a string identity provider")
            }
            val unreadInt = providers.provider<Int> {
                error("fields-only generation realized the versionCode provider")
            }
            val unreadLocales = providers.provider<List<String>> {
                error("fields-only generation realized the locales provider")
            }
            gradle.projectsEvaluated {
                project(":shared").tasks.named<GenerateBuildConfigTask>("generateKmpSsotBuildConfig") {
                    appName.set(unreadString)
                    versionName.set(unreadString)
                    versionCode.set(unreadInt)
                    androidApplicationId.set(unreadString)
                    iosBundleId.set(unreadString)
                    locales.set(unreadLocales)
                }
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm() }
            """.trimIndent(),
        )

        val result = run(":shared:generateKmpSsotBuildConfig")
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)

        val source = File(
            projectDir,
            "shared/build/generated/kmpssot/commonMain/kotlin/com/acme/fields/PublicConfig.kt",
        ).readText()
        assertTrue(source.contains("public const val ANSWER: Int = 42"), source)
        assertFalse(source.contains("appName"), source)
        assertFalse(source.contains("versionCode"), source)
    }

    @Test
    fun `Android logo owns installed outputs and compileSdk 33 emits monochrome resources`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateLogo = true
                cleanupLegacyLogoArtifacts = true
                android { compileSdk = 33 }
                appLogoPngForeground = file("art/fg.png")
                appLogoBackgroundColor = "#FF5500"
            }
            """.trimIndent(),
        )
        writePng("art/fg.png", 512)
        write(
            "androidApp/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:icon="@mipmap/ic_launcher"
                    android:roundIcon="@mipmap/ic_launcher_round" />
            </manifest>
            """.trimIndent(),
        )
        write("androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "<old-adaptive-icon/>")
        write("androidApp/src/main/res/mipmap-mdpi/ic_launcher.webp", "old template")

        run("kmpSsotSyncAndroidLogo")

        val themed = File(projectDir, "androidApp/src/main/res/mipmap-anydpi-v33/ic_launcher.xml")
        assertTrue(themed.isFile, "Android 13 adaptive icon wrapper missing")
        assertTrue(themed.readText().contains("<monochrome"), themed.readText())
        val ownership = File(projectDir, "androidApp/src/main/.kmpssot/android-logo-owned-files-v1")
        assertTrue(ownership.isFile, "Android logo ownership manifest missing")
        assertTrue(
            File(projectDir, ".kmpssot/recovery/android-logo/removal-provenance.tsv").isFile,
            "legacy takeover provenance missing",
        )
        val alignedDiagnostics = run("kmpSsotDoctor")
        assertTrue(alignedDiagnostics.output.contains("[PASS] KMPS003"), alignedDiagnostics.output)
        assertTrue(alignedDiagnostics.output.contains("[PASS] KMPS031"), alignedDiagnostics.output)

        // Ownership hashes alone still describe the old outputs after an input
        // change; provenance must make that stale installation visible.
        writePng("art/fg.png", 513)
        val inputDrift = run("kmpSsotDoctor")
        assertTrue(inputDrift.output.contains("[FAIL] KMPS031"), inputDrift.output)
        assertTrue(inputDrift.output.contains("different logo inputs"), inputDrift.output)

        val generated = File(projectDir, "androidApp/src/main/res/mipmap-mdpi/ic_launcher.png")
        val installedBytes = generated.readBytes()
        run("kmpSsotCleanupLegacyAppLogoArtifacts")
        assertTrue(generated.isFile, "standalone cleanup removed a manifest-owned current icon")
        assertTrue(generated.readBytes().contentEquals(installedBytes), "standalone cleanup changed a managed icon")

        generated.writeText("manual edit")
        val failure = runAndFail("kmpSsotSyncAndroidLogo")
        assertTrue(failure.output.contains("modified generated output"), failure.output)
        assertTrue(generated.readText() == "manual edit", "failed ownership check overwrote user changes")
    }

    @Test
    fun `enabled logo propagation requires a complete logo plan`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot { propagateLogo = true }
            """.trimIndent(),
        )

        val failure = runAndFail("kmpSsotSyncAndroidLogo")

        assertTrue(failure.output.contains("propagateLogo=true requires appLogoPngForeground"), failure.output)
        assertTrue(!failure.output.contains("SKIPPED"), failure.output)
    }

    @Test
    fun `universal iOS logo requires an explicit compatible deployment target`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                propagateLogo = true
                appLogoPngForeground = file("art/fg.png")
                appLogoBackgroundColor = "#FF5500"
            }
            """.trimIndent(),
        )
        writePng("art/fg.png", 64)

        val failure = runAndFail("kmpSsotSyncIosLogo")

        assertTrue(failure.output.contains("ios.deploymentTarget >= 12.0"), failure.output)
        assertTrue(failure.output.contains("Xcode 14"), failure.output)
    }

    @Test
    fun `configured missing logo input fails with the DSL property and path`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateLogo = true
                appLogoPngForeground = file("art/missing.png")
                appLogoBackgroundColor = "#FF5500"
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kmpSsotSyncAndroidLogo")

        assertTrue(failure.output.contains("appLogoPngForeground points to a missing file"), failure.output)
        assertTrue(failure.output.contains("art/missing.png"), failure.output)
    }

    @Test
    fun `multiple Android applications require an exact project selector`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":phone\", \":tablet\")")
        write(
            "buildSrc/build.gradle.kts",
            """
            plugins { java }
            dependencies { implementation(gradleApi()) }
            """.trimIndent(),
        )
        write(
            "buildSrc/src/main/java/fixture/FakeAndroidApplicationPlugin.java",
            """
            package fixture;
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            public final class FakeAndroidApplicationPlugin implements Plugin<Project> {
                @Override public void apply(Project project) { }
            }
            """.trimIndent(),
        )
        write(
            "buildSrc/src/main/resources/META-INF/gradle-plugins/com.android.application.properties",
            "implementation-class=fixture.FakeAndroidApplicationPlugin\n",
        )
        write("phone/build.gradle.kts", "plugins { id(\"com.android.application\") }")
        write("tablet/build.gradle.kts", "plugins { id(\"com.android.application\") }")

        fun rootBuild(selector: String, requestAppSink: Boolean = true) = """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                $selector
                ${if (requestAppSink) """
                propagateLogo = true
                appLogoPngForeground = file("art/fg.png")
                appLogoBackgroundColor = "#112233"
                """.trimIndent() else ""}
            }
        """.trimIndent()
        write("build.gradle.kts", rootBuild("", requestAppSink = false))
        val passive = run("help")
        assertTrue(passive.output.contains("BUILD SUCCESSFUL"), passive.output)

        write(
            "build.gradle.kts",
            rootBuild(
                """
                appName = "Demo"
                propagateBundleId = false
                propagateVersion = false
                """.trimIndent(),
                requestAppSink = false,
            ),
        )
        val ambiguousDoctor = run("kmpSsotDoctor")
        assertTrue(ambiguousDoctor.output.contains("[FAIL] KMPS070"), ambiguousDoctor.output)
        assertTrue(ambiguousDoctor.output.contains("Multiple Android application projects"), ambiguousDoctor.output)
        val ambiguousCheck = runAndFail("kmpSsotCheck")
        assertTrue(ambiguousCheck.output.contains("[FAIL] KMPS070"), ambiguousCheck.output)
        val ambiguousReport = File(projectDir, "build/reports/kmpssot/diagnostics.json").readText()
        assertTrue(ambiguousReport.contains("\"id\": \"KMPS070\""), ambiguousReport)

        val ambiguous = runAndFail("help")
        assertTrue(ambiguous.output.contains("multiple Android application projects"), ambiguous.output)

        write("build.gradle.kts", rootBuild("androidApplicationProjects.add(\":missing\")"))
        val missing = runAndFail("help")
        assertTrue(missing.output.contains("do not apply com.android.application: :missing"), missing.output)

        write(
            "build.gradle.kts",
            rootBuild("androidApplicationProjects.add(\":missing\")", requestAppSink = false),
        )
        val missingDiagnostic = run("kmpSsotDoctor")
        assertTrue(missingDiagnostic.output.contains("[FAIL] KMPS070"), missingDiagnostic.output)
        assertTrue(missingDiagnostic.output.contains("\":missing\""), missingDiagnostic.output)

        write(
            "build.gradle.kts",
            rootBuild("androidApplicationProjects.add(\"phone\")", requestAppSink = false),
        )
        val invalidDiagnostic = run("kmpSsotDoctor")
        assertTrue(invalidDiagnostic.output.contains("[FAIL] KMPS070"), invalidDiagnostic.output)
        assertTrue(invalidDiagnostic.output.contains("invalid absolute Gradle project path"), invalidDiagnostic.output)

        write("build.gradle.kts", rootBuild("androidApplicationProjects.add(\":phone\")"))
        val selected = run("help")
        assertTrue(selected.output.contains("BUILD SUCCESSFUL"), selected.output)

        write(
            "build.gradle.kts",
            rootBuild(
                """
                androidApplicationProjects.addAll(":phone", ":tablet")
                androidAppDirectory.set(layout.projectDirectory.dir("phone"))
                """.trimIndent(),
            ),
        )
        val multiSink = runAndFail("help")
        assertTrue(multiSink.output.contains("one output sink"), multiSink.output)
        assertTrue(multiSink.output.contains("Select exactly one"), multiSink.output)

        write(
            "phone/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"\${appName}\"/></manifest>",
        )
        write(
            "tablet/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"Tablet literal\"/></manifest>",
        )
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo"
                propagateBundleId = false
                androidApplicationProjects.addAll(":phone", ":tablet")
            }
            """.trimIndent(),
        )
        val diagnostic = run("kmpSsotDoctor")
        assertTrue(diagnostic.output.contains("[PASS] KMPS002"), diagnostic.output)
        assertTrue(diagnostic.output.contains("[FAIL] KMPS002"), diagnostic.output)
    }

    @Test
    fun `source migration paths cannot escape the root project`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                iosProjectPath = "../outside/project.pbxproj"
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kmpSsotSyncIosConfig")
        assertTrue(failure.output.contains("iosProjectPath"), failure.output)
        assertTrue(failure.output.contains("contained by the root project"), failure.output)
    }

    @Test
    fun `versionName remains validated when versionCodeOverride bypasses derivation`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                versionName = "x".repeat(256)
                versionCodeOverride = 42
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("versionName"), failure.output)
        assertTrue(failure.output.contains("at most 255 characters"), failure.output)
    }

    @Test
    fun `active shared selection validates the legacy sharedModule fallback`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateLocaleList = false
                sharedModule = "bad/path"
                buildConfig {
                    enabled = true
                    includeIdentity = false
                }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("sharedProjectPath/sharedModule"), failure.output)
        assertTrue(failure.output.contains("not a valid absolute Gradle project path"), failure.output)
    }

    @Test
    fun `active iOS migration validates legacy module-name fallbacks`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateLocaleList = false
                syncIos = true
                propagateSharedModule = true
                sharedModule = "SharedKit"
                oldSharedModuleName = "old-module"
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("iosPreviousSharedModuleName"), failure.output)
        assertTrue(failure.output.contains("Swift module identifier"), failure.output)
    }

    @Test
    fun `disabled iOS mutation ignores dormant migration inputs`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = false
                sanitizeIosProject = true
                propagateSharedModule = true
                iosProjectPath = "../dormant/project.pbxproj"
                iosSharedModuleName = "not a Swift identifier"
                iosPreviousSharedModuleName = "also invalid"
                ios { targetNames.addAll("", "") }
            }
            """.trimIndent(),
        )

        val result = run("help")
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
    }

    @Test
    fun `disabled web generation ignores dormant selectors and generator inputs`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateLocaleList = false
                web {
                    generateIoWorker = false
                    projectPaths.add("not-an-absolute-project-path")
                    browserTargetNames.add("")
                    ioWorkerPackage = "not-a-kotlin-package"
                }
            }
            """.trimIndent(),
        )

        val result = run("help")
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
    }

    @Test
    fun `root SSOT model is frozen before subprojects can mutate it`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":late\")")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot { appName = "Root authority" }
            """.trimIndent(),
        )
        write(
            "late/build.gradle",
            "rootProject.extensions.getByName('kmpSsot').appName.set('Late override')\n",
        )

        val failure = runAndFail("help")
        assertTrue(
            failure.output.contains("final", ignoreCase = true) ||
                failure.output.contains("cannot be changed", ignoreCase = true),
            failure.output,
        )
    }

    @Test
    fun `auto-resolved Android app directory cannot be replaced by a subproject`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":late\")")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot { appName = "Root authority" }
            """.trimIndent(),
        )
        write(
            "late/build.gradle",
            """
            rootProject.extensions.getByName('kmpSsot').androidAppDirectory.set(
                rootProject.layout.projectDirectory.dir('lateOverride')
            )
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(
            failure.output.contains("final", ignoreCase = true) ||
                failure.output.contains("cannot be changed", ignoreCase = true),
            failure.output,
        )
    }

    @Test
    fun `diagnostics report pass and fail findings without failing the build`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo"
                versionName = "1.0.0"
                syncIos = true
            }
            """.trimIndent(),
        )
        write(
            "iosApp/iosApp/Info.plist",
            plist(
                "\t<key>CFBundleName</key>\n\t<string>${'$'}(PRODUCT_NAME)</string>\n" +
                    "\t<key>CFBundleShortVersionString</key>\n\t<string>${'$'}(MARKETING_VERSION)</string>",
            ),
        )

        val result = run("kmpSsotDoctor")
        assertTrue(result.output.contains("Doctor report"), result.output)
        assertTrue(result.output.contains("[PASS]"), result.output)
        assertTrue(result.output.contains("[FAIL]"), result.output)
    }

    @Test
    fun `diagnostics honor KEEP plist conflict policy from the DSL`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Demo"
                propagateVersion = false
                propagateBundleId = false
                propagateLocaleList = false
                syncIos = true
                sanitizeIosProject = true
                ios {
                    plistConflictPolicy = io.github.yuroyami.kmpssot.PlistConflictPolicy.KEEP
                }
            }
            """.trimIndent(),
        )
        write(
            "iosApp/iosApp/Info.plist",
            plist(
                "\t<key>CFBundleDisplayName</key>\n\t<string>Legacy name</string>\n" +
                    "\t<key>CFBundleName</key>\n\t<string>Legacy name</string>",
            ),
        )

        val result = run("kmpSsotDoctor")
        assertTrue(result.output.contains("[WARN] KMPS011"), result.output)
        assertTrue(result.output.contains("conflictPolicy=KEEP"), result.output)
        assertFalse(result.output.contains("[FAIL] KMPS011"), result.output)
    }

    @Test
    fun `doctor and check report invalid model values before normal configuration fails`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                propagateAppName = false
                versionName = "1.2"
                locales.add("not_a_locale")
            }
            tasks.register("inspectSsot") {
                dependsOn("kmpSsotDoctor", "kmpSsotVerify", "kmpSsotPlan")
            }
            """.trimIndent(),
        )

        val alias = run("inspectSsot")
        assertTrue(alias.output.contains("Doctor report"), alias.output)
        assertTrue(alias.output.contains("Resolved single source of truth"), alias.output)
        assertTrue(alias.output.contains("Mutation plan (read-only)"), alias.output)
        assertTrue(alias.output.contains("BUILD SUCCESSFUL"), alias.output)

        val doctor = run("kmpSsotDoctor")
        assertTrue(doctor.output.contains("[FAIL] KMPS040"), doctor.output)
        assertTrue(doctor.output.contains("[FAIL] KMPS050"), doctor.output)
        assertTrue(doctor.output.contains("BUILD SUCCESSFUL"), doctor.output)

        val abbreviatedDoctor = run("kmpSsotDoc")
        assertTrue(abbreviatedDoctor.output.contains("Doctor report"), abbreviatedDoctor.output)
        assertTrue(abbreviatedDoctor.output.contains("BUILD SUCCESSFUL"), abbreviatedDoctor.output)

        val check = runAndFail("kmpSsotCheck")
        assertTrue(check.output.contains("KMPS040"), check.output)
        assertTrue(check.output.contains("KMPS050"), check.output)
        val report = File(projectDir, "build/reports/kmpssot/diagnostics.json")
        assertTrue(report.isFile, "strict diagnostics report was not written before failure")
        assertTrue(report.readText().contains("\"id\": \"KMPS040\""), report.readText())

        val normal = runAndFail("help")
        assertTrue(normal.output.contains("exactly three numeric segments"), normal.output)
    }

    @Test
    fun `diagnostic tasks contain throwing model providers while normal builds fail fast`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName.set(providers.provider<String> {
                    error("deliberate appName provider failure")
                })
                propagateVersion = false
                propagateBundleId = false
                propagateLocaleList = false
            }
            tasks.register("inspectSsot") {
                dependsOn("kmpSsotDoctor", "kmpSsotVerify", "kmpSsotPlan")
            }
            """.trimIndent(),
        )

        val alias = run("inspectSsot")
        assertTrue(alias.output.contains("[FAIL] KMPS902"), alias.output)
        assertTrue(alias.output.contains("deliberate appName provider failure"), alias.output)
        assertTrue(alias.output.contains("appName              = [error:"), alias.output)
        assertTrue(alias.output.contains("BUILD SUCCESSFUL"), alias.output)

        val check = runAndFail("kmpSsotCheck")
        assertTrue(check.output.contains("KMPS902"), check.output)
        val report = File(projectDir, "build/reports/kmpssot/diagnostics.json")
        assertTrue(report.isFile, "provider diagnostic report was not written before failure")
        assertTrue(report.readText().contains("\"id\": \"KMPS902\""), report.readText())

        val normal = runAndFail("help")
        assertTrue(normal.output.contains("deliberate appName provider failure"), normal.output)
    }

    @Test
    fun `doctor and check retain duplicate iOS target names as a stable finding`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                ios { targetNames.addAll("Phone", "Phone") }
            }
            """.trimIndent(),
        )

        val doctor = run("kmpSsotDoctor")
        assertTrue(doctor.output.contains("[FAIL] KMPS071"), doctor.output)
        assertTrue(doctor.output.contains("duplicate target name(s): \"Phone\""), doctor.output)
        assertTrue(doctor.output.contains("BUILD SUCCESSFUL"), doctor.output)

        val check = runAndFail("kmpSsotCheck")
        assertTrue(check.output.contains("[FAIL] KMPS071"), check.output)
        val report = File(projectDir, "build/reports/kmpssot/diagnostics.json").readText()
        assertTrue(report.contains("\"id\": \"KMPS071\""), report)

        val normal = runAndFail("help")
        assertTrue(normal.output.contains("must contain unique, non-blank names"), normal.output)
    }

    @Test
    fun `plan expands operations paths targets and policies without mutation`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                syncIos = true
                sanitizeIosProject = true
                ios { targetNames.add("DemoApp") }
            }
            """.trimIndent(),
        )

        val result = run("kmpSsotPlan")

        assertTrue(result.output.contains("sanitize source Info.plist"), result.output)
        assertTrue(result.output.contains("migrate selected Xcode build settings"), result.output)
        assertTrue(result.output.contains("iosApp/iosApp.xcodeproj/project.pbxproj"), result.output)
        assertTrue(result.output.contains("project.pbxproj$BACKUP_SUFFIX"), result.output)
        assertTrue(result.output.contains(".gradle/kmpssot/rewrite.lock"), result.output)
        assertTrue(result.output.contains("Xcode target DemoApp"), result.output)
        assertTrue(result.output.contains("plistConflictPolicy = FAIL"), result.output)
        assertTrue(result.output.contains("pbxprojScope = explicit targets"), result.output)
        assertTrue(result.output.contains("No files were changed."), result.output)
        assertFalse(File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").exists())
    }

    @Test
    fun `configuration cache is stored and reused`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kmpssot") }
            kmpSsot {
                appName = "Cache Probe"
                versionName = "1.0.0"
                locales.addAll("en-us", "en-US", "fr")
                iosSharedModuleName = "SharedKit"
                androidAppDirectory.set(layout.projectDirectory.dir("customAndroidApp"))
            }
            """.trimIndent(),
        )
        val arguments = arrayOf(
            "kmpSsotVerify",
            "kmpSsotPlan",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        val first = run(*arguments)
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        val second = run(*arguments)
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertTrue(second.output.contains("Resolved single source of truth"), second.output)
        assertTrue(second.output.contains("Mutation plan (read-only)"), second.output)
        assertTrue(second.output.contains("locales              = en-US, fr"), second.output)
        assertTrue(second.output.contains("iosSharedModuleName  = SharedKit"), second.output)
        assertTrue(second.output.contains("customAndroidApp"), second.output)
    }
}
