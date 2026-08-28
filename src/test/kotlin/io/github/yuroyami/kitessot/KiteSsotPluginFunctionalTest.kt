package io.github.yuroyami.kitessot

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
 * classloader as kitessot and exercising the documented root `apply false` setup.
 */
class KiteSsotPluginFunctionalTest {

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

    /** [settingsWithShared] plus the extra modules a Compose Desktop fixture needs. */
    private fun settingsWithSharedAndDesktop(vararg desktopModules: String) =
        settingsWithShared() + desktopModules.joinToString("") { "\ninclude(\"$it\")" }

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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo App"
                version = "1.2.3"
                id = "com.demo.app"
                locales { pin("fr", "pt-BR") }
                version("1.2.3") { ios { marketingVersion = "1.2.3"; pin = "42" } }
                ios {
                    rewrite { cleanPlist = true }
                }
            }
            """.trimIndent(),
        )
        write("iosApp/iosApp.xcodeproj/project.pbxproj", pbxProject("Phone"))
        write("iosApp/iosApp/Info.plist", plist())

        // The config task plans plist + pbxproj changes as one transaction; both
        // operations are explicit opt-ins and neither is attached to compilation.
        run("kiteInternalIosConfig")

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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo"
                version = "1.2.3"
                id = "com.demo.app"
                version("1.2.3") { ios { marketingVersion = "1.2.3"; pin = "7" } }
                ios {
                    rewrite {
                        $targetSelector
                    }
                }
            }
        """.trimIndent()
        write("build.gradle.kts", build(""))
        val original = pbxProject("Phone", "TV")
        write("iosApp/iosApp.xcodeproj/project.pbxproj", original)

        val ambiguous = runAndFail("kiteInternalIosConfig")
        assertTrue(ambiguous.output.contains("multiple application targets"), ambiguous.output)
        assertTrue(
            File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").readText() == original,
            "ambiguous migration changed the pbxproj",
        )

        write("build.gradle.kts", build("targets(\"Phone\")"))
        run("kiteInternalIosConfig")
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo"
                version = "1.2.3"
                version("1.2.3") { ios { marketingVersion = "1.2.3"; pin = "7" } }
                ios {
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kiteInternalIosConfig")
        assertTrue(failure.output.contains("Configured pbxproj does not exist"), failure.output)
        assertTrue(failure.output.contains("project.pbxproj"), failure.output)
    }

    @Test
    fun `shared module migration requires and uses an explicit from and to`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                ios {
                    rewrite {
                        renameSharedModule(from = "shared", to = "composeApp")
                    }
                }
            }
            """.trimIndent(),
        )
        write("iosApp/iosApp.xcodeproj/project.pbxproj", pbxProject("Phone"))
        write("iosApp/Podfile", "pod 'Utils', :path => '../Utils'\npod 'shared', :path => '../shared'\n")
        write("iosApp/iosApp/ContentView.swift", "import Utils\nimport shared\n")

        run("kiteInternalIosConfig")

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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Migrated"
                ios {
                    rewrite {
                        renameSharedModule(from = "shared", to = "composeApp")
                    }
                }
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

        val failure = runAndFail("kiteInternalIosConfig")

        assertTrue(
            failure.output.contains("maximum depth $MAX_IOS_SWIFT_TRAVERSAL_DEPTH"),
            failure.output,
        )
        assertTrue(File(projectDir, pbxprojPath).readText() == originalPbxproj, "pbxproj was partially committed")
        assertTrue(File(projectDir, shallowSwiftPath).readText() == originalSwift, "Swift source was partially committed")
        assertFalse(File(projectDir, "$pbxprojPath.kitessot.bak").exists(), "backup was created before planning finished")
    }

    @Test
    fun `iOS logo takeover creates an external backup and ownership manifest`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                ios {
                    deploymentTarget = "12.0"
                    rewrite { }
                }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#80FF5500"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )
        writePng("art/fg.png", 64)
        val appiconset = "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"
        write("$appiconset/Contents.json", "{ \"user-authored\" : true }")
        writePng("$appiconset/AppIcon-40.png", 40)

        val result = run("kiteInternalIosLogo")

        val catalogIdentity = SyncIosLogoTask.catalogIdentity(projectDir, File(projectDir, appiconset))
        val recoveryRoot = File(projectDir, ".kitessot/recovery/ios-appicon/$catalogIdentity")
        assertTrue(File(recoveryRoot, "removal-provenance.tsv").isFile, "backup provenance missing")
        val backedUpContents = recoveryRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "Contents.json" && it.readText().contains("user-authored") }
        assertTrue(backedUpContents != null, "user-owned Contents.json was not backed up before takeover")

        val ownership = File(
            projectDir,
            "iosApp/iosApp/.kitessot/$catalogIdentity/owned-files-v1",
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
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                dryRun = true
                web {
                    ioWorker {
                        projects(":shared")
                        targets("js")
                        packageName = "$workerPackage"
                    }
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
            import com.acme.gen.kiteSsotOffload
            suspend fun workerProbe(): String =
                kiteSsotOffload("(value) => value", "ok", 1_000L)
            """.trimIndent(),
        )

        val compile = run(":shared:compileKotlinJs")
        assertTrue(compile.output.contains("BUILD SUCCESSFUL"), compile.output)

        val generated = File(
            projectDir,
            "shared/build/generated/kitessot/jsMain/kotlin/com/acme/gen/KiteSsotIoWorker.kt",
        )
        assertTrue(generated.isFile, "generated worker file missing: ${generated.path}")
        assertTrue(generated.readText().contains("suspend fun kiteSsotOffload"), generated.readText())

        // A package move must delete only the previous manifest-owned source.
        write("build.gradle.kts", rootBuild("com.acme.other"))
        run(":shared:kiteInternalIoWorkerJs")
        val moved = File(
            projectDir,
            "shared/build/generated/kitessot/jsMain/kotlin/com/acme/other/KiteSsotIoWorker.kt",
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
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                web {
                    ioWorker {
                        projects(":shared")
                    }
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
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
                locales { pin("en", "pt-BR") }
                dryRun = true
                buildConfig {
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
            "shared/build/generated/kitessot/commonMain/kotlin/com/acme/gen/AppConfig.kt",
        )
        assertTrue(generated.isFile, "generated BuildConfig missing: ${generated.path}")
        val source = generated.readText()
        assertTrue(source.contains("public const val appName: String = \"Demo\""), source)
        assertTrue(source.contains("public const val versionName: String = \"1.2.3\""), source)
        assertTrue(source.contains("public const val versionCode: Int = 1001002030"), source)
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
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
                buildConfig {
                    packageName = "com.acme.gen"
                    className = "AppConfig"
                }
            }
            """.trimIndent(),
        )
        val identityOnly = run(":shared:kiteInternalBuildConfig")
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
            import io.github.yuroyami.kitessot.GenerateBuildConfigTask

            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                buildConfig {
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
                project(":shared").tasks.named<GenerateBuildConfigTask>("kiteInternalBuildConfig") {
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

        val result = run(":shared:kiteInternalBuildConfig")
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)

        val source = File(
            projectDir,
            "shared/build/generated/kitessot/commonMain/kotlin/com/acme/fields/PublicConfig.kt",
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                modules { androidAppDirectory.set(layout.projectDirectory.dir("androidApp")) }
                android { compileSdk = 33 }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#FF5500"
                    rewrite { replaceOld = true }
                }
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

        run("kiteInternalAndroidLogo")

        val themed = File(projectDir, "androidApp/src/main/res/mipmap-anydpi-v33/ic_launcher.xml")
        assertTrue(themed.isFile, "Android 13 adaptive icon wrapper missing")
        assertTrue(themed.readText().contains("<monochrome"), themed.readText())
        val ownership = File(projectDir, "androidApp/src/main/.kitessot/android-logo-owned-files-v1")
        assertTrue(ownership.isFile, "Android logo ownership manifest missing")
        assertTrue(
            File(projectDir, ".kitessot/recovery/android-logo/removal-provenance.tsv").isFile,
            "legacy takeover provenance missing",
        )
        val alignedDiagnostics = run("kiteDoctor")
        assertTrue(alignedDiagnostics.output.contains("[PASS] KMPS003"), alignedDiagnostics.output)
        assertTrue(alignedDiagnostics.output.contains("[PASS] KMPS031"), alignedDiagnostics.output)

        // Ownership hashes alone still describe the old outputs after an input
        // change; provenance must make that stale installation visible.
        writePng("art/fg.png", 513)
        val inputDrift = run("kiteDoctor")
        assertTrue(inputDrift.output.contains("[FAIL] KMPS031"), inputDrift.output)
        assertTrue(inputDrift.output.contains("different logo inputs"), inputDrift.output)

        val generated = File(projectDir, "androidApp/src/main/res/mipmap-mdpi/ic_launcher.png")
        val installedBytes = generated.readBytes()
        run("kiteInternalLegacyIconCleanup")
        assertTrue(generated.isFile, "standalone cleanup removed a manifest-owned current icon")
        assertTrue(generated.readBytes().contentEquals(installedBytes), "standalone cleanup changed a managed icon")

        generated.writeText("manual edit")
        val failure = runAndFail("kiteInternalAndroidLogo")
        assertTrue(failure.output.contains("modified generated output"), failure.output)
        assertTrue(generated.readText() == "manual edit", "failed ownership check overwrote user changes")
    }

    @Test
    fun `enabled logo propagation requires a complete logo plan`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot { logo { rewrite { } } }
            """.trimIndent(),
        )

        val failure = runAndFail("kiteInternalAndroidLogo")

        assertTrue(failure.output.contains("logo { } } requires foreground"), failure.output)
        assertTrue(!failure.output.contains("SKIPPED"), failure.output)
    }

    @Test
    fun `universal iOS logo requires an explicit compatible deployment target`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                ios { rewrite { } }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#FF5500"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )
        writePng("art/fg.png", 64)

        val failure = runAndFail("kiteInternalIosLogo")

        assertTrue(failure.output.contains("ios.deploymentTarget >= 12.0"), failure.output)
        assertTrue(failure.output.contains("Xcode 14"), failure.output)
    }

    @Test
    fun `configured missing logo input fails with the DSL property and path`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                logo {
                    foreground = file("art/missing.png")
                    backgroundColor = "#FF5500"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kiteInternalAndroidLogo")

        assertTrue(failure.output.contains("points to a missing file"), failure.output)
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                $selector
                ${if (requestAppSink) """
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#112233"
                    rewrite { }
                }
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
                """.trimIndent(),
                requestAppSink = false,
            ),
        )
        val ambiguousDoctor = run("kiteDoctor")
        assertTrue(ambiguousDoctor.output.contains("[FAIL] KMPS070"), ambiguousDoctor.output)
        assertTrue(ambiguousDoctor.output.contains("Multiple Android application projects"), ambiguousDoctor.output)
        val ambiguousCheck = runAndFail("kiteCheck")
        assertTrue(ambiguousCheck.output.contains("[FAIL] KMPS070"), ambiguousCheck.output)
        val ambiguousReport = File(projectDir, "build/reports/kitessot/diagnostics.json").readText()
        assertTrue(ambiguousReport.contains("\"id\": \"KMPS070\""), ambiguousReport)

        val ambiguous = runAndFail("help")
        assertTrue(ambiguous.output.contains("multiple Android application projects"), ambiguous.output)

        write("build.gradle.kts", rootBuild("modules { androidApps(\":missing\") }"))
        val missing = runAndFail("help")
        assertTrue(missing.output.contains("do not apply com.android.application: :missing"), missing.output)

        write(
            "build.gradle.kts",
            rootBuild("modules { androidApps(\":missing\") }", requestAppSink = false),
        )
        val missingDiagnostic = run("kiteDoctor")
        assertTrue(missingDiagnostic.output.contains("[FAIL] KMPS070"), missingDiagnostic.output)
        assertTrue(missingDiagnostic.output.contains("\":missing\""), missingDiagnostic.output)

        write(
            "build.gradle.kts",
            rootBuild("modules { androidApps(\"phone\") }", requestAppSink = false),
        )
        val invalidDiagnostic = run("kiteDoctor")
        assertTrue(invalidDiagnostic.output.contains("[FAIL] KMPS070"), invalidDiagnostic.output)
        assertTrue(invalidDiagnostic.output.contains("invalid absolute Gradle project path"), invalidDiagnostic.output)

        write("build.gradle.kts", rootBuild("modules { androidApps(\":phone\") }"))
        val selected = run("help")
        assertTrue(selected.output.contains("BUILD SUCCESSFUL"), selected.output)

        write(
            "build.gradle.kts",
            rootBuild(
                """
                modules {
                    androidApps(":phone", ":tablet")
                    androidAppDirectory.set(layout.projectDirectory.dir("phone"))
                }
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo"
                modules { androidApps(":phone", ":tablet") }
            }
            """.trimIndent(),
        )
        val diagnostic = run("kiteDoctor")
        assertTrue(diagnostic.output.contains("[PASS] KMPS002"), diagnostic.output)
        assertTrue(diagnostic.output.contains("[FAIL] KMPS002"), diagnostic.output)
    }

    @Test
    fun `version remains validated when an explicit versionCode bypasses derivation`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                version("x".repeat(256)) { android { pin = 42 } }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("kiteSsot { version }"), failure.output)
        assertTrue(failure.output.contains("at most 255 characters"), failure.output)
    }



    @Test
    fun `root SSOT model is frozen before subprojects can mutate it`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":late\")")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot { appName = "Root authority" }
            """.trimIndent(),
        )
        write(
            "late/build.gradle",
            "rootProject.extensions.getByName('kiteSsot').appName.set('Late override')\n",
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot { appName = "Root authority" }
            """.trimIndent(),
        )
        write(
            "late/build.gradle",
            """
            rootProject.extensions.getByName('kiteSsot').modules.androidAppDirectory.set(
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo"
                version = "1.0.0"
                ios { rewrite { } }
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

        val result = run("kiteDoctor")
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Demo"
                ios {
                    rewrite {
                        cleanPlist = true
                        onConflict = io.github.yuroyami.kitessot.PlistConflictPolicy.KEEP
                    }
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

        val result = run("kiteDoctor")
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                version = "1.2"
                locales { pin("not_a_locale") }
            }
            tasks.register("inspectSsot") {
                dependsOn("kiteDoctor", "kiteVerify", "kitePlan")
            }
            """.trimIndent(),
        )

        val alias = run("inspectSsot")
        assertTrue(alias.output.contains("Doctor report"), alias.output)
        assertTrue(alias.output.contains("Resolved single source of truth"), alias.output)
        assertTrue(alias.output.contains("Mutation plan (read-only)"), alias.output)
        assertTrue(alias.output.contains("BUILD SUCCESSFUL"), alias.output)

        val doctor = run("kiteDoctor")
        assertTrue(doctor.output.contains("[FAIL] KMPS040"), doctor.output)
        assertTrue(doctor.output.contains("[FAIL] KMPS050"), doctor.output)
        assertTrue(doctor.output.contains("BUILD SUCCESSFUL"), doctor.output)

        val abbreviatedDoctor = run("kiteDoc")
        assertTrue(abbreviatedDoctor.output.contains("Doctor report"), abbreviatedDoctor.output)
        assertTrue(abbreviatedDoctor.output.contains("BUILD SUCCESSFUL"), abbreviatedDoctor.output)

        val check = runAndFail("kiteCheck")
        assertTrue(check.output.contains("KMPS040"), check.output)
        assertTrue(check.output.contains("KMPS050"), check.output)
        val report = File(projectDir, "build/reports/kitessot/diagnostics.json")
        assertTrue(report.isFile, "strict diagnostics report was not written before failure")
        assertTrue(report.readText().contains("\"id\": \"KMPS040\""), report.readText())

        val normal = runAndFail("help")
        assertTrue(normal.output.contains("numeric segments (x.y.z) are required"), normal.output)
    }

    @Test
    fun `diagnostic tasks contain throwing model providers while normal builds fail fast`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName.set(providers.provider<String> {
                    error("deliberate appName provider failure")
                })
            }
            tasks.register("inspectSsot") {
                dependsOn("kiteDoctor", "kiteVerify", "kitePlan")
            }
            """.trimIndent(),
        )

        val alias = run("inspectSsot")
        assertTrue(alias.output.contains("[FAIL] KMPS902"), alias.output)
        assertTrue(alias.output.contains("deliberate appName provider failure"), alias.output)
        assertTrue(alias.output.contains("appName              = [error:"), alias.output)
        assertTrue(alias.output.contains("BUILD SUCCESSFUL"), alias.output)

        val check = runAndFail("kiteCheck")
        assertTrue(check.output.contains("KMPS902"), check.output)
        val report = File(projectDir, "build/reports/kitessot/diagnostics.json")
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                ios { rewrite { targets("Phone", "Phone") } }
            }
            """.trimIndent(),
        )

        val doctor = run("kiteDoctor")
        assertTrue(doctor.output.contains("[FAIL] KMPS071"), doctor.output)
        assertTrue(doctor.output.contains("duplicate target name(s): \"Phone\""), doctor.output)
        assertTrue(doctor.output.contains("BUILD SUCCESSFUL"), doctor.output)

        val check = runAndFail("kiteCheck")
        assertTrue(check.output.contains("[FAIL] KMPS071"), check.output)
        val report = File(projectDir, "build/reports/kitessot/diagnostics.json").readText()
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
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                ios {
                    rewrite {
                        cleanPlist = true
                        targets("DemoApp")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = run("kitePlan")

        assertTrue(result.output.contains("sanitize source Info.plist"), result.output)
        assertTrue(result.output.contains("migrate selected Xcode build settings"), result.output)
        assertTrue(result.output.contains("iosApp/iosApp.xcodeproj/project.pbxproj"), result.output)
        assertTrue(result.output.contains("project.pbxproj$BACKUP_SUFFIX"), result.output)
        assertTrue(result.output.contains(".gradle/kitessot/rewrite.lock"), result.output)
        assertTrue(result.output.contains("Xcode target DemoApp"), result.output)
        // Padding is computed from the longest key, so match the pair, not the spacing.
        assertTrue(Regex("""ios\.rewrite\.onConflict\s+= FAIL""").containsMatchIn(result.output), result.output)
        assertTrue(Regex("""pbxprojScope\s+= explicit targets""").containsMatchIn(result.output), result.output)
        assertTrue(result.output.contains("No files were changed."), result.output)
        assertFalse(File(projectDir, "iosApp/iosApp.xcodeproj/project.pbxproj").exists())
    }

    @Test
    fun `configuration cache is stored and reused`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Cache Probe"
                version = "1.0.0"
                locales { pin("en-us", "en-US", "fr") }
                ios {
                    rewrite { renameSharedModule(from = "shared", to = "SharedKit") }
                }
                modules {
                    androidAppDirectory.set(layout.projectDirectory.dir("customAndroidApp"))
                }
            }
            """.trimIndent(),
        )
        val arguments = arrayOf(
            "kiteVerify",
            "kitePlan",
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
        assertTrue(second.output.contains("ios.sync renameTo    = SharedKit"), second.output)
        assertTrue(second.output.contains("customAndroidApp"), second.output)
    }

    @Test
    fun `a presence-gated mutation task survives the configuration cache`() {
        // Regression test: the five mutation tasks' onlyIf specs used to re-walk
        // ext's nested extension container at EXECUTION time, which the
        // configuration cache cannot restore, and every one of them failed on a
        // cached rerun with UnknownDomainObjectException.
        writePng("art/fg.png", 8)
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "CacheProbe"
                version = "1.0.0"
                modules { androidAppDirectory = file("androidApp") }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )
        val arguments = arrayOf(
            "kiteInternalAndroidLogo",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        val first = run(*arguments)
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        val second = run(*arguments)
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertTrue(second.output.contains("BUILD SUCCESSFUL"), second.output)
    }

    @Test
    fun `resilient diagnostics report a malformed version instead of failing the configuration cache`() {
        // Regression test: kiteDoctor/kiteCheck bound the scheme-derived
        // versionCode and iosBuildNumber providers directly. Those throw on a
        // version their scheme cannot encode, and the configuration cache
        // evaluates bound task inputs while storing the cache entry, before the
        // task's own resilient resolve() wrapper ever runs.
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "Probe"
                version = "1.4"
            }
            """.trimIndent(),
        )
        val arguments = arrayOf(
            "kiteDoctor",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        val first = run(*arguments)
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(first.output.contains("[FAIL] KMPS050"), first.output)
        val second = run(*arguments)
        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertTrue(second.output.contains("[FAIL] KMPS050"), second.output)
    }



    @Test
    fun `ios publishedBuildNumber rejects a stale rebuild and accepts a bumped one`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "PublishedGuard"
                version("1.4.0") { ios { shipped = "1001004000" } }
                ios {
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")
        assertTrue(failure.output.contains("must be greater than the published baseline"), failure.output)

        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "PublishedGuard"
                version("1.4.0") { ios { reupload = 1; shipped = "1001004000" } }
                ios {
                    rewrite { }
                }
            }
            """.trimIndent(),
        )
        val success = run("help")
        assertTrue(success.output.contains("BUILD SUCCESSFUL"), success.output)
    }

    @Test
    fun `-Pkitessot dryRun and backups override the DSL for one invocation`() {
        writePng("art/fg.png", 8)
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "CliMirror"
                version = "1.0.0"
                modules { androidAppDirectory = file("androidApp") }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val previewed = run("kiteInternalAndroidLogo", "-Pkitessot.dryRun=true")
        assertTrue(previewed.output.contains("dry-run"), previewed.output)
        assertFalse(File(projectDir, "androidApp/src/main/res").exists())

        val written = run("kiteInternalAndroidLogo")
        assertFalse(written.output.contains("dry-run"), written.output)
        assertTrue(File(projectDir, "androidApp/src/main/res").exists())
    }

    @Test
    fun `the sole KMP project is detected as the shared module for buildConfig`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                appName = "Detected"
                version = "1.2.3"
                id = "com.acme.app"
                buildConfig {
                    packageName = "com.acme.gen"
                    className = "AppConfig"
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

        val result = run(":shared:kiteInternalBuildConfig")

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
        val generated = File(
            projectDir,
            "shared/build/generated/kitessot/commonMain/kotlin/com/acme/gen/AppConfig.kt",
        )
        assertTrue(generated.isFile, "generated BuildConfig missing: ${generated.path}")
        assertTrue(generated.readText().contains("public const val appName: String = \"Detected\""))
    }

    @Test
    fun `locales are discovered from the detected shared module's compose resources`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                appName = "LocaleProbe"
                version = "1.0.0"
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
        write("shared/src/commonMain/composeResources/values-en/strings.xml", "<resources/>")
        write("shared/src/commonMain/composeResources/values-fr/strings.xml", "<resources/>")

        val result = run("kiteVerify")

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
        assertTrue(result.output.contains("en, fr"), result.output)
    }

    @Test
    fun `two KMP projects without an explicit selection fail naming both candidates`() {
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
            include(":core")
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                appName = "Ambiguous"
                version = "1.0.0"
                id = "com.acme.app"
                buildConfig { packageName = "com.acme.gen" }
            }
            """.trimIndent(),
        )
        write("shared/build.gradle.kts", "plugins { id(\"org.jetbrains.kotlin.multiplatform\") }\nkotlin { jvm() }")
        write("core/build.gradle.kts", "plugins { id(\"org.jetbrains.kotlin.multiplatform\") }\nkotlin { jvm() }")

        val failure = runAndFail("help")

        assertTrue(failure.output.contains(":shared"), failure.output)
        assertTrue(failure.output.contains(":core"), failure.output)
        assertTrue(failure.output.contains("modules { shared"), failure.output)
    }

    @Test
    fun `a shared-scoped feature says which module to name when none is selected`() {
        // No project in this build applies Kotlin Multiplatform, so detection has
        // zero candidates and the error must say what to configure.
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "NoShared"
                version = "1.0.0"
                id = "com.acme.app"
                buildConfig { packageName = "demo.build" }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("help")

        assertTrue(failure.output.contains("no shared project is selected"), failure.output)
        assertTrue(failure.output.contains("modules { shared"), failure.output)
    }

    @Test
    fun `logo installation fails closed instead of writing into the root source tree`() {
        writePng("art/fg.png", 8)
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        // No Android application anywhere, and no explicit output directory.
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "NoAndroidApp"
                version = "1.0.0"
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        val failure = runAndFail("kiteInternalAndroidLogo")

        assertTrue(failure.output.contains("Android application"), failure.output)
        assertFalse(
            File(projectDir, "src/main/res").exists(),
            "the root project is not an Android app, so nothing may be written there",
        )
    }

    @Test
    fun `the logo dry-run previews the deletions it would perform, not only the writes`() {
        writePng("art/fg.png", 8)
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write("androidApp/build.gradle.kts", "")
        // A template icon that collides with a generated PNG: the real run deletes it.
        write("androidApp/src/main/res/mipmap-hdpi/ic_launcher.webp", "x")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "PreviewTruth"
                version = "1.0.0"
                modules { androidAppDirectory = file("androidApp") }
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#102A43"
                    rewrite { replaceOld = true }
                }
            }
            """.trimIndent(),
        )

        val preview = run("kiteInternalAndroidLogo", "-Pkitessot.dryRun=true")

        // The write half was always previewed.
        assertTrue(preview.output.contains("would write Android logo"), preview.output)
        // The destructive half must be previewed too, naming the file it removes.
        assertTrue(preview.output.contains("ic_launcher.webp"), preview.output)
        assertTrue(File(projectDir, "androidApp/src/main/res/mipmap-hdpi/ic_launcher.webp").exists())
    }

    @Test
    fun `a misspelled safety flag fails the build and mutates nothing`() {
        writePng("art/fg.png", 8)
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins { id("io.github.yuroyami.kitessot") }
            kiteSsot {
                appName = "CliMirror"
                version = "1.0.0"
                logo {
                    foreground = file("art/fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
            }
            """.trimIndent(),
        )

        // "treu" used to parse as false, turning a requested preview into a real write.
        val failure = runAndFail("kiteInternalAndroidLogo", "-Pkitessot.dryRun=treu")
        assertTrue(failure.output.contains("kitessot.dryRun"), failure.output)
        assertTrue(failure.output.contains("treu"), failure.output)
        assertFalse(File(projectDir, "src/main/res").exists(), "no source may be written")

        // Same for backups, where the default is ON, so a typo would silently remove protection.
        val backupsFailure = runAndFail("kiteInternalAndroidLogo", "-Pkitessot.backups=treu")
        assertTrue(backupsFailure.output.contains("kitessot.backups"), backupsFailure.output)
        assertFalse(File(projectDir, "src/main/res").exists(), "no source may be written")
    }

    @Test
    fun `the desktop block is accepted and reported by verify`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                appName = "Demo"
                version = "1.2.3"
                id("com.acme.app") { desktop { suffix = ".desktop" } }
                version("1.2.3") { desktop { reupload = 2 } }
            }
            """.trimIndent(),
        )
        write("shared/build.gradle.kts", """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm() }
        """.trimIndent())

        val result = run("kiteVerify")
        assertTrue(result.output.contains("com.acme.app.desktop"), result.output)
    }

    /**
     * Design section 9 requires the resolved Windows upgrade code always be
     * printed by both `kiteVerify` and `kiteDoctor`, and section 10
     * requires the same of the derived Linux package name; only doctor had them.
     */
    @Test
    fun `kiteVerify prints the resolved Windows upgrade code and Linux package name`() {
        write("settings.gradle.kts", settingsWithShared())
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
                desktop {
                    deriveUpgradeUuid = true
                }
            }
            """.trimIndent(),
        )
        write("shared/build.gradle.kts", """
            plugins { id("org.jetbrains.kotlin.multiplatform") }
            kotlin { jvm() }
        """.trimIndent())

        val result = run("kiteVerify")

        assertTrue(result.output.contains(deriveUpgradeUuid("com.acme.app")), result.output)
        assertTrue(result.output.contains(deriveLinuxPackageName("Demo")), result.output)
    }

    // --- Compose Desktop ------------------------------------------------------
    // Every fixture below applies org.jetbrains.kotlin.plugin.compose next to
    // org.jetbrains.compose: ComposePlugin fails configuration outright without it.

    private fun sharedJvmModule() = """
        plugins { id("org.jetbrains.kotlin.multiplatform") }
        kotlin { jvm() }
    """.trimIndent()

    private fun desktopRootBuild(body: String = "", logo: String = "") = """
        plugins {
            id("org.jetbrains.kotlin.multiplatform") apply false
            id("org.jetbrains.kotlin.plugin.compose") apply false
            id("org.jetbrains.compose") apply false
            id("io.github.yuroyami.kitessot")
        }
        kiteSsot {
            modules { shared = ":shared" }
            appName = "Demo"
            version = "1.2.3"
            id = "com.acme.app"
            $body
            $logo
        }
    """.trimIndent()

    private fun composeModulePlugins() = """
        plugins {
            id("org.jetbrains.kotlin.multiplatform")
            id("org.jetbrains.kotlin.plugin.compose")
            id("org.jetbrains.compose")
        }
        kotlin { jvm() }
    """.trimIndent()

    /**
     * The load-bearing ordering test. Compose reads its plain `var` identity fields
     * inside an `afterEvaluate` it registers when the module applies it, so a
     * KiteSSOT value only lands if KiteSSOT's own callback was registered first.
     * `StaleName` in the output means KiteSSOT lost that race.
     */
    @Test
    fun `the SSOT replaces a package name the desktop module declared itself`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write("build.gradle.kts", desktopRootBuild())
        write("shared/build.gradle.kts", sharedJvmModule())
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application {
                    mainClass = "MainKt"
                    nativeDistributions { packageName = "StaleName" }
                }
            }
            tasks.register("printDesktopIdentity") {
                val distributions = compose.desktop.application.nativeDistributions
                doLast {
                    println("PACKAGE_NAME=" + distributions.packageName)
                    println("PACKAGE_VERSION=" + distributions.packageVersion)
                    println("BUNDLE_ID=" + distributions.macOS.bundleID)
                    println("BUILD_VERSION=" + distributions.macOS.packageBuildVersion)
                }
            }
            """.trimIndent(),
        )

        val result = run(":desktopApp:printDesktopIdentity")

        assertTrue(result.output.contains("PACKAGE_NAME=Demo"), result.output)
        assertTrue(result.output.contains("PACKAGE_VERSION=1.2.3"), result.output)
        assertTrue(result.output.contains("BUNDLE_ID=com.acme.app"), result.output)
        assertTrue(result.output.contains("BUILD_VERSION=1001002030"), result.output)
        assertTrue(
            result.output.contains("StaleName"),
            "the drift warning should name what was replaced: ${result.output}",
        )
    }

    @Test
    fun `the native application receives the same identity as the JVM application`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":nativeApp"))
        write("build.gradle.kts", desktopRootBuild("id(\"com.acme.app\") { desktop { suffix = \".desktop\" } }"))
        write("shared/build.gradle.kts", sharedJvmModule())
        write(
            "nativeApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                nativeApplication {
                    distributions { packageName = "StaleNative" }
                }
            }
            tasks.register("printNativeIdentity") {
                val distributions = compose.desktop.nativeApplication.distributions
                doLast {
                    println("PACKAGE_NAME=" + distributions.packageName)
                    println("PACKAGE_VERSION=" + distributions.packageVersion)
                    println("BUNDLE_ID=" + distributions.macOS.bundleID)
                    println("BUILD_VERSION=" + distributions.macOS.packageBuildVersion)
                }
            }
            """.trimIndent(),
        )

        val result = run(":nativeApp:printNativeIdentity")

        assertTrue(result.output.contains("PACKAGE_NAME=Demo"), result.output)
        assertTrue(result.output.contains("PACKAGE_VERSION=1.2.3"), result.output)
        assertTrue(result.output.contains("BUNDLE_ID=com.acme.app.desktop"), result.output)
        assertTrue(result.output.contains("BUILD_VERSION=1001002030"), result.output)
    }

    @Test
    fun `two desktop applications without a selector fail and name both candidates`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":deskA", ":deskB"))
        write("build.gradle.kts", desktopRootBuild())
        write("shared/build.gradle.kts", sharedJvmModule())
        listOf("deskA", "deskB").forEach { module ->
            write(
                "$module/build.gradle.kts",
                """
                ${composeModulePlugins()}
                compose.desktop {
                    application { mainClass = "MainKt" }
                }
                """.trimIndent(),
            )
        }

        val failure = runAndFail("help")

        assertTrue(failure.output.contains(":deskA"), failure.output)
        assertTrue(failure.output.contains(":deskB"), failure.output)
        assertTrue(failure.output.contains("desktopApps"), failure.output)
    }

    /**
     * Detection must read the initialization flags without touching `application`.
     * Touching it initializes the lazy delegate, and Compose then configures
     * packaging tasks in a module that only draws UI.
     */
    @Test
    fun `a Compose module that is not an application gains no packaging tasks`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":ui", ":desktopApp"))
        write("build.gradle.kts", desktopRootBuild())
        write("shared/build.gradle.kts", sharedJvmModule())
        write("ui/build.gradle.kts", composeModulePlugins())
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application { mainClass = "MainKt" }
            }
            """.trimIndent(),
        )

        val uiTasks = run(":ui:tasks", "--all")
        assertFalse(uiTasks.output.contains("createDistributable"), uiTasks.output)
        assertFalse(uiTasks.output.contains("packageDistributionForCurrentOS"), uiTasks.output)

        // The same run must still produce those tasks for the real application, or
        // the assertions above would pass for the wrong reason.
        val appTasks = run(":desktopApp:tasks", "--all")
        assertTrue(appTasks.output.contains("createDistributable"), appTasks.output)
        assertTrue(appTasks.output.contains("packageDistributionForCurrentOS"), appTasks.output)
    }

    /**
     * Icons are generated into `build/` and wired through `iconFile`, a real
     * `RegularFileProperty`. Every declared target format's package task reads its
     * icon from the current host's DSL platform block regardless of that task's own
     * target OS, so one task registration and `--dry-run` (never real jpackage
     * execution) is enough on any CI host.
     */
    @Test
    fun `packaging depends on generated desktop icons without an explicit dependsOn`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write(
            "build.gradle.kts",
            desktopRootBuild(
                logo = """
                logo {
                    foreground = file("art/logo_fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
                """.trimIndent(),
            ),
        )
        write("shared/build.gradle.kts", sharedJvmModule())
        writePng("art/logo_fg.png", 512)
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application {
                    mainClass = "MainKt"
                    nativeDistributions {
                        targetFormats(
                            org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                            org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                            org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                        )
                    }
                }
            }
            """.trimIndent(),
        )

        val result = run(":desktopApp:packageDistributionForCurrentOS", "--dry-run")

        assertTrue(
            result.output.contains(":desktopApp:kiteInternalDesktopIcons SKIPPED"),
            "the icon task must be in the packaging graph: ${result.output}",
        )
    }


    /**
     * The hard configuration failure above used to sit before the resilient-diagnostic
     * early return, so `kiteDoctor` itself died on the exact misconfiguration it
     * exists to explain. It now sits after that return, so this must both report
     * KMPS081 and exit successfully: doctor never fails the build (README.md).
     */

    @Test
    fun `kiteDoctor reports compose compatibility and desktop application selection for a real project`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write(
            "build.gradle.kts",
            desktopRootBuild(
                logo = """
                logo {
                    foreground = file("art/logo_fg.png")
                    backgroundColor = "#102A43"
                    rewrite { }
                }
                """.trimIndent(),
            ),
        )
        write("shared/build.gradle.kts", sharedJvmModule())
        writePng("art/logo_fg.png", 512)
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application { mainClass = "MainKt" }
            }
            """.trimIndent(),
        )

        val result = run("kiteDoctor")

        assertTrue(result.output.contains("[PASS] KMPS082 Compose Gradle plugin compatibility"), result.output)
        // No explicit modules { desktopApps } selector; the sole detected app is
        // unambiguous, which KMPS070 (its Android counterpart) also reports as SKIPPED.
        assertTrue(result.output.contains("[SKIP] KMPS083 Desktop application selection"), result.output)
        assertTrue(result.output.contains("[PASS] KMPS080 Desktop identity propagation"), result.output)
    }

    /**
     * `:ui` applies Compose but configures no application. Naming it through
     * `modules { desktopApps(...) }` must be rejected with a message pointing at it,
     * before `DesktopWiring.write()` initializes Compose's lazy `application`
     * delegate on a module with no `mainClass`.
     */
    @Test
    fun `an explicitly selected module that is not a desktop app fails and names it`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":ui"))
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("org.jetbrains.kotlin.plugin.compose") apply false
                id("org.jetbrains.compose") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules {
                    shared = ":shared"
                    desktopApps(":ui")
                }
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
                desktop { }
            }
            """.trimIndent(),
        )
        write("shared/build.gradle.kts", sharedJvmModule())
        write("ui/build.gradle.kts", composeModulePlugins())

        val failure = runAndFail("help")

        assertTrue(failure.output.contains(":ui"), failure.output)
    }

    @Test
    fun `the derived upgrade uuid is applied only when asked and never overwrites an explicit value`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write("build.gradle.kts", desktopRootBuild("desktop { deriveUpgradeUuid = true }"))
        write("shared/build.gradle.kts", sharedJvmModule())
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application {
                    mainClass = "MainKt"
                }
            }
            tasks.register("printUpgradeUuid") {
                val windows = compose.desktop.application.nativeDistributions.windows
                doLast { println("RESOLVED=" + windows.upgradeUuid) }
            }
            """.trimIndent(),
        )

        val derived = run(":desktopApp:printUpgradeUuid")
        assertTrue(derived.output.contains("RESOLVED=" + deriveUpgradeUuid("com.acme.app")), derived.output)

        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application {
                    mainClass = "MainKt"
                    nativeDistributions {
                        windows { upgradeUuid = "8c247f56-9724-4b95-8503-b47c5c1b0e35" }
                    }
                }
            }
            tasks.register("printUpgradeUuid") {
                val windows = compose.desktop.application.nativeDistributions.windows
                doLast { println("RESOLVED=" + windows.upgradeUuid) }
            }
            """.trimIndent(),
        )

        val kept = run(":desktopApp:printUpgradeUuid")
        assertTrue(kept.output.contains("RESOLVED=8c247f56-9724-4b95-8503-b47c5c1b0e35"), kept.output)
    }

    @Test
    fun `a desktop build number that does not beat the published baseline fails the build`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write("build.gradle.kts", desktopRootBuild("version(\"1.2.3\") { desktop { shipped = \"9999999999\" } }"))
        write("shared/build.gradle.kts", sharedJvmModule())
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application { mainClass = "MainKt" }
            }
            """.trimIndent(),
        )

        val result = runAndFail("help")
        assertTrue(result.output.contains("9999999999"), result.output)
        assertTrue(result.output.contains("desktop {"), result.output)
        assertTrue(!result.output.contains("ios {"), result.output)
    }

    @Test
    fun `applying Compose to the root project fails with a clear message`() {
        write("settings.gradle.kts", "rootProject.name = \"fixture\"")
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("org.jetbrains.kotlin.plugin.compose")
                id("org.jetbrains.compose")
                id("io.github.yuroyami.kitessot")
            }
            kotlin { jvm() }
            compose.desktop {
                application {
                    mainClass = "MainKt"
                }
            }
            kiteSsot {
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
                desktop { }
            }
            """.trimIndent(),
        )

        val result = runAndFail("help")

        assertTrue(result.output.contains("root project"), result.output)
        assertTrue(result.output.contains("apply false"), result.output)
    }

    /**
     * Regression test for the crash where the desktop-app census was computed a
     * second, unguarded time: a project that never touches `desktop { }` must not
     * pay for a Compose subproject existing elsewhere in the build.
     *
     * This fixture cannot reproduce the original NoClassDefFoundError itself: every
     * fixture here shares one TestKit classloader with Compose on it (see the class
     * KDoc), so `COMPOSE_ON_CLASSPATH` is always true in this test process. The bug
     * needed Compose applied only inside a subproject's own `plugins { }` block with
     * no root `apply false`, landing it in a sibling classloader kitessot cannot see.
     */
    @Test
    fun `a compose subproject with no desktop block configured does not crash the build`() {
        write("settings.gradle.kts", settingsWithSharedAndDesktop(":desktopApp"))
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform") apply false
                id("org.jetbrains.kotlin.plugin.compose") apply false
                id("org.jetbrains.compose") apply false
                id("io.github.yuroyami.kitessot")
            }
            kiteSsot {
                modules { shared = ":shared" }
                appName = "Demo"
                version = "1.2.3"
                id = "com.acme.app"
            }
            """.trimIndent(),
        )
        write("shared/build.gradle.kts", sharedJvmModule())
        write(
            "desktopApp/build.gradle.kts",
            """
            ${composeModulePlugins()}
            compose.desktop {
                application { mainClass = "MainKt" }
            }
            """.trimIndent(),
        )

        val result = run("help")

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
    }
}
