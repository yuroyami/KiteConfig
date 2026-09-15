package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * End-to-end coverage against the real Android Gradle plugin at both ends of
 * kiteconfig's documented compatibility range.
 *
 * These fixtures intentionally use Groovy build scripts. That keeps the
 * assertions independent of which AGP Kotlin DSL accessors happen to be
 * compiled into the fixture, while all values are still read from the real AGP
 * application/library extensions after `finalizeDsl` has run.
 */
@Tag("agp-compatibility")
class AgpCompatibilityFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, content: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `Gradle 8_5 floor loads and configures the published consumer plugin`() {
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    gradlePluginPortal()
                }
            }
            rootProject.name = 'gradle-floor-fixture'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Gradle Floor App'
                version = '1.2.3'
                appId = 'dev.matrix.gradlefloor'
            }

            tasks.register('verifyGradleFloor') {
                doLast {
                    assert kiteConfig.appName.get() == 'Gradle Floor App'
                    assert kiteConfig.version.get() == '1.2.3'
                    println 'GRADLE_FLOOR_OK 8.5'
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("8.5")
            .withArguments("--stacktrace", "verifyGradleFloor")
            .build()

        assertTrue(result.output.contains("GRADLE_FLOOR_OK 8.5"), result.output)
    }

    @Test
    fun `KGP 2_4_0 applies on the Gradle 8_5 floor with fields-only BuildConfig`() {
        writeKgpBuildConfigFixture()
        val result = runKgpBuildConfigFixture("8.5")

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
        assertTrue(result.output.contains("Configuration cache entry stored."), result.output)
        assertTrue(result.output.contains("Gradle 8.5) is deprecated"), result.output)
        assertTrue(result.output.contains("Gradle 8.14.4 in Kotlin 2.5.0"), result.output)
        assertGeneratedBuildConfig()
    }

    @Test
    fun `KGP 2_4_0 fields-only BuildConfig reuses cache on the current Gradle line`() {
        writeKgpBuildConfigFixture()

        val first = runKgpBuildConfigFixture("9.5.1")
        val second = runKgpBuildConfigFixture("9.5.1")

        assertTrue(first.output.contains("BUILD SUCCESSFUL"), first.output)
        assertTrue(first.output.contains("Configuration cache entry stored."), first.output)
        assertTrue(second.output.contains("BUILD SUCCESSFUL"), second.output)
        assertTrue(second.output.contains("Reusing configuration cache."), second.output)
        assertGeneratedBuildConfig()
    }

    @Test
    fun `AGP 8_5_2 floor uses classic application and library adapters`() {
        val result = runMatrixFixture(
            agpVersion = "8.5.2",
            gradleVersion = "8.9",
            localeSeed = "defaultConfig.resourceConfigurations.addAll(['fr', 'xxhdpi', 'car'])",
            localeAssertion = """
                assert dc.resourceConfigurations.contains('en') : dc.resourceConfigurations
                assert dc.resourceConfigurations.contains('pt-rBR') : dc.resourceConfigurations
                assert !dc.resourceConfigurations.contains('fr') : dc.resourceConfigurations
                assert dc.resourceConfigurations.contains('xxhdpi') : dc.resourceConfigurations
                assert dc.resourceConfigurations.contains('car') : dc.resourceConfigurations
            """.trimIndent(),
        )

        assertTrue(result.output.contains("AGP_MATRIX_OK 8.5.2"), result.output)
        assertDriftWarnings(result)
    }

    @Test
    fun `AGP 9_3_1 current uses modern application and library adapters`() {
        val result = runMatrixFixture(
            agpVersion = "9.3.1",
            gradleVersion = "9.5.1",
            localeSeed = "androidResources.localeFilters.add('fr')",
            localeAssertion = """
                assert androidExt.androidResources.localeFilters.contains('en') : androidExt.androidResources.localeFilters
                assert androidExt.androidResources.localeFilters.contains('pt-rBR') : androidExt.androidResources.localeFilters
                assert !androidExt.androidResources.localeFilters.contains('fr') : androidExt.androidResources.localeFilters
            """.trimIndent(),
        )

        assertTrue(result.output.contains("AGP_MATRIX_OK 9.3.1"), result.output)
        assertDriftWarnings(result)
    }

    @Test
    fun `AGP 9_3_1 and KGP 2_4_0 configure a KMP-native Android library with cache reuse`() {
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'kmp-native-android-fixture'
            include ':shared'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'org.jetbrains.kotlin.multiplatform' version '2.4.0' apply false
                id 'com.android.kotlin.multiplatform.library' version '9.3.1' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'KMP Native Android Fixture'
                version = '3.4.5'
                appId = 'dev.matrix.kmpnative'
                android {
                    compileSdk = 35
                    minSdk = 24
                }
            }

            tasks.register('verifyKmpNativeAndroid') {
                dependsOn ':shared:assertConfigKmpNativeAndroid'
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle",
            """
            plugins {
                id 'org.jetbrains.kotlin.multiplatform'
                id 'com.android.kotlin.multiplatform.library'
            }

            abstract class VerifyKmpNativeAndroidTask extends DefaultTask {
                @Input
                abstract Property<Integer> getCompileSdk()

                @Input
                abstract Property<Integer> getMinSdk()

                @TaskAction
                void verifyValues() {
                    assert compileSdk.get() == 35 : compileSdk.get()
                    assert minSdk.get() == 24 : minSdk.get()
                    println 'KMP_NATIVE_ANDROID_OK AGP 9.3.1 KGP 2.4.0'
                }
            }

            def verification = tasks.register(
                'assertConfigKmpNativeAndroid',
                VerifyKmpNativeAndroidTask,
            )

            kotlin {
                android {
                    namespace = 'fixture.kmpnative'
                    compileSdk = 34
                    minSdk = 21
                }
            }

            androidComponents {
                finalizeDsl { dsl ->
                    verification.configure {
                        compileSdk.set(dsl.compileSdk)
                        minSdk.set(dsl.minSdk)
                    }
                }
            }
            """.trimIndent(),
        )
        write("shared/src/androidMain/AndroidManifest.xml", "<manifest/>")

        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { sdkDir ->
            write("local.properties", "sdk.dir=${sdkDir.replace("\\", "\\\\")}")
        }

        val arguments = listOf(
            "--stacktrace",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "verifyKmpNativeAndroid",
        )
        val first = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("9.5.1")
            .withArguments(arguments)
            .build()
        val second = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("9.5.1")
            .withArguments(arguments)
            .build()

        assertTrue(first.output.contains("KMP_NATIVE_ANDROID_OK AGP 9.3.1 KGP 2.4.0"), first.output)
        assertTrue(first.output.contains("Configuration cache entry stored."), first.output)
        assertTrue(
            first.output.contains(
                ":shared declares values the single source of truth replaces: " +
                    "compileSdk 34 -> 35, minSdk 21 -> 24",
            ),
            first.output,
        )
        assertTrue(second.output.contains("KMP_NATIVE_ANDROID_OK AGP 9.3.1 KGP 2.4.0"), second.output)
        assertTrue(second.output.contains("Reusing configuration cache."), second.output)
    }

    /**
     * The two lines a 1.0.0 project had to hand-write are gone.
     *
     * This runs the real manifest merger, because that is the only thing that
     * can say whether a generated manifest actually outranks the module's own.
     * A manifest with no label and no theme is what a fresh Android project
     * ships with; both values still have to reach the merged output.
     */
    @Test
    fun `the generated overlay carries the label and splash theme with no manifest edit`() {
        val sdk = androidSdkDirectory()
        assumeTrue(sdk != null, "no Android SDK on this host")

        publishPluginFixture()
        write("local.properties", "sdk.dir=${sdk!!.path.replace("\\", "\\\\")}")
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'overlay-fixture'
            include ':app'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '9.3.1' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Overlay App'
                appId = 'dev.matrix.overlay'
                version = '1.0.0'
                logo {
                    foreground = file('art/logo.png')
                    background = color('#102A43')
                }
                splash { enabled = true }
                modules { androidApps(':app') }
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                    splash { theme = 'android:Theme.Material.Light.NoActionBar' }
                }
                skip(ios)
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }
            android { namespace = 'dev.matrix.overlay' }

            tasks.register('printMergedManifest') {
                dependsOn 'processDebugMainManifest'
                // aapt2 has to resolve @style/KiteSplash out of the generated
                // resources, so this fails if the splash res dir never arrives.
                dependsOn 'processDebugResources'
                def merged = layout.buildDirectory
                    .file('intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml')
                doLast {
                    println 'MERGED_START'
                    println merged.get().asFile.text
                    println 'MERGED_END'
                }
            }
            """.trimIndent(),
        )
        writeSquarePng("art/logo.png")
        // A fresh Android project: no label, no theme, nothing to edit.
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>",
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("9.5.1")
            .withArguments("--stacktrace", ":app:printMergedManifest")
            .build()

        val merged = result.output.substringAfter("MERGED_START").substringBefore("MERGED_END")
        assertTrue(merged.contains("android:label=\"Overlay App\""), result.output)
        assertTrue(merged.contains("android:theme=\"@style/KiteSplash\""), result.output)
    }

    /** The same overlay on the AGP floor, where the source API could still differ. */
    @Test
    fun `the overlay reaches the merged manifest on the AGP floor too`() {
        val sdk = androidSdkDirectory()
        assumeTrue(sdk != null, "no Android SDK on this host")

        publishPluginFixture()
        write("local.properties", "sdk.dir=${sdk!!.path.replace("\\", "\\\\")}")
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'overlay-floor-fixture'
            include ':app'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '8.5.2' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Floor Overlay App'
                appId = 'dev.matrix.overlayfloor'
                version = '1.0.0'
                modules { androidApps(':app') }
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
                skip(ios)
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }
            android { namespace = 'dev.matrix.overlayfloor' }

            tasks.register('printMergedManifest') {
                dependsOn 'processDebugMainManifest'
                def merged = layout.buildDirectory
                    .file('intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml')
                doLast {
                    println 'MERGED_START'
                    println merged.get().asFile.text
                    println 'MERGED_END'
                }
            }
            """.trimIndent(),
        )
        // A hand-written label the overlay has to take over, not trip over.
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<application android:label=\"Hand Written\"/></manifest>",
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("8.9")
            .withArguments("--stacktrace", ":app:printMergedManifest")
            .build()

        val merged = result.output.substringAfter("MERGED_START").substringBefore("MERGED_END")
        assertTrue(merged.contains("android:label=\"Floor Overlay App\""), result.output)
        assertTrue(!merged.contains("Hand Written"), result.output)
    }

    private fun writeSquarePng(path: String, side: Int = 64) {
        val file = File(projectDir, path).also { it.parentFile.mkdirs() }
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color(0x10, 0x2A, 0x43)
            fillRect(0, 0, side, side)
            dispose()
        }
        ImageIO.write(image, "PNG", file)
    }

    /** Finds an SDK the same way a consumer build would, without guessing. */
    private fun androidSdkDirectory(): File? {
        sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
            .map(::File)
            .firstOrNull { it.resolve("platforms").isDirectory }
            ?.let { return it }
        val local = File(System.getProperty("user.dir")).resolve("local.properties")
        if (!local.isFile) return null
        return local.readLines()
            .firstOrNull { it.trimStart().startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
            ?.replace("\\\\", "\\")
            ?.trim()
            ?.let(::File)
            ?.takeIf { it.resolve("platforms").isDirectory }
    }

    private fun runMatrixFixture(
        agpVersion: String,
        gradleVersion: String,
        localeSeed: String,
        localeAssertion: String,
    ): BuildResult {
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'agp-$agpVersion-fixture'
            include ':app', ':secondaryApp', ':library'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '$agpVersion' apply false
                id 'com.android.library' version '$agpVersion' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Matrix App'
                version = '2.3.4'
                appId = 'dev.matrix.ssot'
                locales {
                    tags = ['en', 'pt-BR']
                }
                jvm { target = 17 }
                modules {
                    androidApps(':app')
                }
                android {
                    locales {
                        filterResources = true
                    }
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
            }

            tasks.register('verifyConfigAndroidMatrix') {
                dependsOn ':app:assertConfigApplication', ':secondaryApp:assertGlobalAndroidValues', ':library:assertConfigLibrary'
                doLast {
                    println 'AGP_MATRIX_OK $agpVersion'
                }
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }

            android {
                namespace = 'fixture.application'
                compileSdk = 34
                $localeSeed
                defaultConfig {
                    applicationId = 'fixture.before'
                    minSdk = 21
                    targetSdk = 34
                    versionCode = 1
                    versionName = '0.1.0'
                    manifestPlaceholders.appName = 'Before'
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }

            tasks.register('assertConfigApplication') {
                doLast {
                    def androidExt = project.extensions.getByName('android')
                    def dc = androidExt.defaultConfig
                    assert androidExt.compileSdk == 35 : androidExt.compileSdk
                    assert dc.applicationId == 'dev.matrix.ssot' : dc.applicationId
                    assert dc.versionName == '2.3.4' : dc.versionName
                    assert dc.versionCode == 1002003040 : dc.versionCode
                    assert dc.minSdk == 24 : dc.minSdk
                    assert dc.targetSdk == 35 : dc.targetSdk
                    assert dc.manifestPlaceholders.appName == 'Matrix App' : dc.manifestPlaceholders
                    assert androidExt.compileOptions.sourceCompatibility == JavaVersion.VERSION_17
                    assert androidExt.compileOptions.targetCompatibility == JavaVersion.VERSION_17
                    ${localeAssertion.prependIndent("                    ").trimStart()}
                }
            }
            """.trimIndent(),
        )
        write(
            "secondaryApp/build.gradle",
            """
            plugins { id 'com.android.application' }

            android {
                namespace = 'fixture.secondary'
                compileSdk = 34
                defaultConfig {
                    applicationId = 'fixture.secondary'
                    minSdk = 21
                    targetSdk = 34
                    versionCode = 7
                    versionName = '0.7.0'
                    manifestPlaceholders.appName = 'Secondary Before'
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }

            tasks.register('assertGlobalAndroidValues') {
                doLast {
                    def androidExt = project.extensions.getByName('android')
                    def dc = androidExt.defaultConfig
                    assert androidExt.compileSdk == 35 : androidExt.compileSdk
                    assert dc.minSdk == 24 : dc.minSdk
                    assert dc.targetSdk == 35 : dc.targetSdk
                    assert androidExt.compileOptions.sourceCompatibility == JavaVersion.VERSION_17
                    assert androidExt.compileOptions.targetCompatibility == JavaVersion.VERSION_17
                    assert dc.applicationId == 'fixture.secondary' : dc.applicationId
                    assert dc.versionName == '0.7.0' : dc.versionName
                    assert dc.versionCode == 7 : dc.versionCode
                    assert dc.manifestPlaceholders.appName == 'Secondary Before' : dc.manifestPlaceholders
                }
            }
            """.trimIndent(),
        )
        write(
            "library/build.gradle",
            """
            plugins { id 'com.android.library' }

            android {
                namespace = 'fixture.library'
                compileSdk = 34
                defaultConfig { minSdk = 21 }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }

            tasks.register('assertConfigLibrary') {
                doLast {
                    def androidExt = project.extensions.getByName('android')
                    def dc = androidExt.defaultConfig
                    assert androidExt.compileSdk == 35 : androidExt.compileSdk
                    assert dc.minSdk == 24 : dc.minSdk
                    assert androidExt.compileOptions.sourceCompatibility == JavaVersion.VERSION_17
                    assert androidExt.compileOptions.targetCompatibility == JavaVersion.VERSION_17
                }
            }
            """.trimIndent(),
        )
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"\${appName}\"/></manifest>",
        )
        write(
            "secondaryApp/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"\${appName}\"/></manifest>",
        )
        write("library/src/main/AndroidManifest.xml", "<manifest/>")

        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { sdkDir ->
            write("local.properties", "sdk.dir=${sdkDir.replace("\\", "\\\\")}")
        }

        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion(gradleVersion)
            .withArguments("--stacktrace", "verifyConfigAndroidMatrix")
            .build()
    }

    private fun writeKgpBuildConfigFixture() {
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = 'kgp-build-config-fixture'
            include ':shared'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'org.jetbrains.kotlin.multiplatform' version '2.4.0' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                modules {
                    shared = ':shared'
                }
                buildConfig {
                    includeIdentity = false
                    packageName = 'fixture.generated'
                    stringField('API_ORIGIN', 'https://fixture.invalid')
                }
            }
            """.trimIndent(),
        )
        write(
            "shared/build.gradle",
            """
            plugins { id 'org.jetbrains.kotlin.multiplatform' }

            kotlin { jvm() }
            """.trimIndent(),
        )
        write(
            "shared/src/commonMain/kotlin/UseGeneratedConfig.kt",
            """
            package fixture

            import fixture.generated.KiteBuildConfig

            val apiOrigin: String = KiteBuildConfig.API_ORIGIN
            """.trimIndent(),
        )
    }

    private fun runKgpBuildConfigFixture(gradleVersion: String): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir)
        .withGradleVersion(gradleVersion)
        .withArguments(
            "--stacktrace",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            ":shared:compileKotlinJvm",
        )
        .build()

    /**
     * The fixture sets no `className`, so this also pins the default name. A
     * rename of that default must fail here rather than in a consumer's build.
     */
    private fun assertGeneratedBuildConfig() {
        val generated = File(
            projectDir,
            "shared/build/generated/kiteconfig/commonMain/kotlin/fixture/generated/KiteBuildConfig.kt",
        )
        assertTrue(generated.isFile, "Generated KiteBuildConfig is missing: $generated")
        assertTrue(generated.readText().contains("API_ORIGIN"), generated.readText())
        assertTrue(
            generated.readText().contains("public object KiteBuildConfig"),
            generated.readText(),
        )
    }

    /**
     * Publish only what a real consumer receives: the plugin JAR, its minimal
     * POM, and the Gradle plugin marker. `withPluginClasspath()` is avoided on
     * purpose because TestKit injects that classpath in a scope separate from
     * externally resolved AGP plugins, which cannot exercise typed linkage.
     */
    private fun publishPluginFixture() {
        val sourceJar = File(
            requireNotNull(System.getProperty("kiteconfig.test.pluginJar")) {
                "agpCompatibilityTest did not provide the plugin JAR path"
            },
        )
        check(sourceJar.isFile) { "Plugin JAR does not exist: $sourceJar" }

        val mainBase = "plugin-repository/io/github/yuroyami/kiteconfig/test-fixture"
        val markerBase =
            "plugin-repository/io/github/yuroyami/kiteconfig/io.github.yuroyami.kiteconfig.gradle.plugin/test-fixture"
        val publishedJar = File(projectDir, "$mainBase/kiteconfig-test-fixture.jar")
        publishedJar.parentFile.mkdirs()
        sourceJar.copyTo(publishedJar, overwrite = true)
        write(
            "$mainBase/kiteconfig-test-fixture.pom",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.github.yuroyami</groupId>
              <artifactId>kiteconfig</artifactId>
              <version>test-fixture</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent(),
        )
        write(
            "$markerBase/io.github.yuroyami.kiteconfig.gradle.plugin-test-fixture.pom",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.github.yuroyami.kiteconfig</groupId>
              <artifactId>io.github.yuroyami.kiteconfig.gradle.plugin</artifactId>
              <version>test-fixture</version>
              <packaging>pom</packaging>
              <dependencies>
                <dependency>
                  <groupId>io.github.yuroyami</groupId>
                  <artifactId>kiteconfig</artifactId>
                  <version>test-fixture</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
    }

    @Test
    fun `AGP 8_5_2 library module with propagate version off does not evaluate an unencodable version`() {
        // Regression test: the AGP 8 adapter used to resolve the scheme-derived
        // versionCode unconditionally, even for a library module and even with
        // propagate.version off, so a version its scheme could not encode broke
        // an AGP 8 build that the identical AGP 9 build accepted.
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = 'agp8-library-version-off-fixture'
            include ':library'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.library' version '8.5.2' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'LibraryOnly'
                version = '1.4.150'
                android { version { enabled = false } }
                ios { version { enabled = false } }
                desktop { version { enabled = false } }
            }

            tasks.register('verifyLibraryBuilds') {
                dependsOn ':library:assertLibraryConfigured'
            }
            """.trimIndent(),
        )
        write(
            "library/build.gradle",
            """
            plugins { id 'com.android.library' }

            android {
                namespace = 'fixture.library'
                compileSdk = 34
                defaultConfig { minSdk = 21 }
            }

            tasks.register('assertLibraryConfigured') {
                doLast { println 'AGP8_LIBRARY_VERSION_OFF_OK' }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("8.9")
            .withArguments("--stacktrace", "verifyLibraryBuilds")
            .build()

        assertTrue(result.output.contains("AGP8_LIBRARY_VERSION_OFF_OK"), result.output)
    }

    @Test
    fun `AGP 9_3_1 receives SDK levels from the KiteConfig alone and still runs the diagnostic tasks`() {
        writeConfigOnlySdkLevelsFixture()

        // A regular task: the wiring supplies compileSdk/minSdk/targetSdk from nothing.
        val supplied = runConfigOnlyFixture("verifyConfigSuppliedSdkLevels")
        assertTrue(supplied.output.contains("KiteConfig_SUPPLIED_SDK_OK"), supplied.output)

        // The diagnostic tasks used to skip the wiring, so AGP failed configuration
        // ("does not specify compileSdk") before the diagnostic could report anything.
        val verify = runConfigOnlyFixture("kiteVerify")
        assertTrue(Regex("""compileSdk\s+= 35""").containsMatchIn(verify.output), verify.output)

        val doctor = runConfigOnlyFixture("kiteDoctor")
        assertTrue(doctor.output.contains("Doctor report"), doctor.output)
    }

    @Test
    fun `kiteDoctor survives a version its scheme cannot encode while the KiteConfig supplies compileSdk`() {
        // patch 150 exceeds the default scheme's 0..99 budget, so the versionCode
        // provider throws when the wiring resolves it. On a diagnostic invocation
        // that failure must be skipped, not fatal: the SDK levels still reach AGP
        // (or configuration dies for want of compileSdk) and the doctor reports
        // the version problem as a finding.
        writeConfigOnlySdkLevelsFixture(version = "1.4.150")

        val doctor = runConfigOnlyFixture("kiteDoctor")
        assertTrue(doctor.output.contains("Doctor report"), doctor.output)
        assertTrue(doctor.output.contains("KTCNFG050"), doctor.output)
    }

    private fun runConfigOnlyFixture(task: String): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir)
        .withGradleVersion("9.5.1")
        .withArguments("--stacktrace", task)
        .build()

    /** An application module that declares no SDK level anywhere: the KiteConfig is the only source. */
    private fun writeConfigOnlySdkLevelsFixture(version: String = "2.3.4") {
        publishPluginFixture()
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'ssot-only-sdk-fixture'
            include ':app'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '9.3.1' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Config Only App'
                version = '$version'
                appId = 'dev.matrix.ssotonly'
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
            }

            tasks.register('verifyConfigSuppliedSdkLevels') {
                dependsOn ':app:assertSdkLevelsSupplied'
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }

            // No compileSdk, minSdk, or targetSdk anywhere in this module.
            android {
                namespace = 'fixture.ssotonly'
            }

            tasks.register('assertSdkLevelsSupplied') {
                doLast {
                    def androidExt = project.extensions.getByName('android')
                    def dc = androidExt.defaultConfig
                    assert androidExt.compileSdk == 35 : androidExt.compileSdk
                    assert dc.minSdk == 24 : dc.minSdk
                    assert dc.targetSdk == 35 : dc.targetSdk
                    println 'KiteConfig_SUPPLIED_SDK_OK'
                }
            }
            """.trimIndent(),
        )
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"\${appName}\"/></manifest>",
        )

        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { sdkDir ->
            write("local.properties", "sdk.dir=${sdkDir.replace("\\", "\\\\")}")
        }
    }

    /** The matrix fixture seeds module values the KiteConfig replaces; every adapter must say so. */
    private fun assertDriftWarnings(result: BuildResult) {
        assertTrue(
            result.output.contains(
                ":app declares values the single source of truth replaces: " +
                    "applicationId fixture.before -> dev.matrix.ssot, versionName 0.1.0 -> 2.3.4, " +
                    "versionCode 1 -> 1002003040, compileSdk 34 -> 35, minSdk 21 -> 24, targetSdk 34 -> 35",
            ),
            result.output,
        )
        assertTrue(
            result.output.contains(
                ":secondaryApp declares values the single source of truth replaces: " +
                    "compileSdk 34 -> 35, minSdk 21 -> 24, targetSdk 34 -> 35",
            ),
            result.output,
        )
        assertTrue(
            result.output.contains(
                ":library declares values the single source of truth replaces: " +
                    "compileSdk 34 -> 35, minSdk 21 -> 24",
            ),
            result.output,
        )
    }

    /**
     * The debug build type gets its own identifier and label through the real
     * manifest merger, and the release build type keeps the release values.
     */
    @Test
    fun `the debug build type gets its own identifier and label`() {
        val sdk = androidSdkDirectory()
        assumeTrue(sdk != null, "no Android SDK on this host")
        writeDebugIdentityFixture(agpVersion = "9.3.1", sdk = sdk!!)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("9.5.1")
            .withArguments("--stacktrace", ":app:printMergedManifests")
            .build()

        assertDebugIdentity(result)
    }

    /** The same split on the AGP floor, through the Java adapter. */
    @Test
    fun `the debug build type gets its own identifier and label on the AGP floor too`() {
        val sdk = androidSdkDirectory()
        assumeTrue(sdk != null, "no Android SDK on this host")
        writeDebugIdentityFixture(agpVersion = "8.5.2", sdk = sdk!!)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("8.9")
            .withArguments("--stacktrace", ":app:printMergedManifests")
            .build()

        assertDebugIdentity(result)
    }

    private fun assertDebugIdentity(result: BuildResult) {
        val debug = result.output.substringAfter("DEBUG_START").substringBefore("DEBUG_END")
        val release = result.output.substringAfter("RELEASE_START").substringBefore("RELEASE_END")
        assertTrue(debug.contains("package=\"dev.matrix.overlay.debug\""), result.output)
        assertTrue(debug.contains("android:label=\"Overlay Dev\""), result.output)
        assertTrue(release.contains("package=\"dev.matrix.overlay\""), result.output)
        assertTrue(release.contains("android:label=\"Overlay App\""), result.output)
    }

    private fun writeDebugIdentityFixture(agpVersion: String, sdk: File) {
        publishPluginFixture()
        write("local.properties", "sdk.dir=${sdk.path.replace("\\", "\\\\")}")
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'debug-identity-fixture'
            include ':app'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '$agpVersion' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Overlay App'
                appId = 'dev.matrix.overlay'
                version = '1.0.0'
                modules { androidApps(':app') }
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
                debug {
                    appId { suffix = '.debug' }
                    appName = 'Overlay Dev'
                }
                skip(ios)
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }
            android { namespace = 'dev.matrix.overlay' }

            tasks.register('printMergedManifests') {
                dependsOn 'processDebugMainManifest', 'processReleaseMainManifest'
                def debug = layout.buildDirectory
                    .file('intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml')
                def release = layout.buildDirectory
                    .file('intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml')
                doLast {
                    println 'DEBUG_START'
                    println debug.get().asFile.text
                    println 'DEBUG_END'
                    println 'RELEASE_START'
                    println release.get().asFile.text
                    println 'RELEASE_END'
                }
            }
            """.trimIndent(),
        )
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>",
        )
    }

    /**
     * Only the APK proves that the generated debug icons win over the installed
     * release icons. Both variants are built and each launcher icon is read back.
     */
    @Test
    fun `the debug build type gets its own launcher icon and release keeps its own`() {
        val sdk = androidSdkDirectory()
        assumeTrue(sdk != null, "no Android SDK on this host")

        publishPluginFixture()
        write("local.properties", "sdk.dir=${sdk!!.path.replace("\\", "\\\\")}")
        write(
            "settings.gradle",
            """
            pluginManagement {
                repositories {
                    maven { url = uri(file('plugin-repository')) }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = 'debug-icon-fixture'
            include ':app'
            """.trimIndent(),
        )
        write(
            "build.gradle",
            """
            plugins {
                id 'com.android.application' version '9.3.1' apply false
                id 'io.github.yuroyami.kiteconfig' version 'test-fixture'
            }

            kiteConfig {
                appName = 'Icon App'
                appId = 'dev.matrix.icon'
                version = '1.0.0'
                autoApply = true
                modules { androidApps(':app') }
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
                logo {
                    foreground = file('art/logo.png')
                    background = color('#102A43')
                }
                debug {
                    appId { suffix = '.debug' }
                    logo { background = color('#B00020') }
                }
                skip(ios)
            }
            """.trimIndent(),
        )
        write(
            "app/build.gradle",
            """
            plugins { id 'com.android.application' }
            android {
                namespace = 'dev.matrix.icon'
                lint { checkReleaseBuilds = false }
            }
            """.trimIndent(),
        )
        writeCenteredMarkPng("art/logo.png")
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
                "<application android:icon=\"@mipmap/ic_launcher\" android:roundIcon=\"@mipmap/ic_launcher_round\"/>" +
                "</manifest>",
        )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion("9.5.1")
            .withArguments("--stacktrace", ":app:assembleDebug", ":app:assembleRelease")
            .build()

        assertEquals(0xB00020, launcherPlate(File(projectDir, "app/build/outputs/apk/debug/app-debug.apk"), sdk))
        assertEquals(0x102A43, launcherPlate(File(projectDir, "app/build/outputs/apk/release/app-release-unsigned.apk"), sdk))
    }

    /**
     * The corner colour of the xxxhdpi legacy launcher icon inside [apk].
     *
     * Release builds shorten resource paths, so the file is found the way Android
     * finds it: through the resource table, as `aapt2 dump resources` prints it.
     */
    private fun launcherPlate(apk: File, sdk: File): Int {
        assertTrue(apk.isFile, "APK missing: $apk")
        val aapt2 = sdk.resolve("build-tools").listFiles().orEmpty()
            .map { it.resolve("aapt2") }
            .filter(File::isFile)
            .maxByOrNull { it.parentFile.name }
        assumeTrue(aapt2 != null, "no aapt2 in the Android SDK build-tools")
        val process = ProcessBuilder(aapt2!!.path, "dump", "resources", apk.path).redirectErrorStream(true).start()
        val dump = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), dump)
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim().endsWith(" mipmap/ic_launcher") }
        assertTrue(start >= 0, "no mipmap/ic_launcher in $apk:\n$dump")
        val path = lines.drop(start + 1)
            .takeWhile { !it.trim().startsWith("resource ") && !it.trim().startsWith("type ") }
            .firstOrNull { "xxxhdpi" in it && "(file)" in it && it.trim().endsWith("type=PNG") }
            ?.substringAfter("(file) ")
            ?.substringBefore(" type=")
        assertTrue(path != null, "no xxxhdpi PNG for mipmap/ic_launcher in $apk:\n$dump")
        ZipFile(apk).use { zip ->
            val icon = zip.getInputStream(zip.getEntry(path!!)).use(ImageIO::read)
            return icon.getRGB(2, 2) and 0xFFFFFF
        }
    }

    /** A mark with transparent corners, so the plate colour is what a corner pixel shows. */
    private fun writeCenteredMarkPng(path: String, side: Int = 512) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color.WHITE
            fillRect(side / 4, side / 4, side / 2, side / 2)
            dispose()
        }
        ImageIO.write(image, "PNG", file)
    }
}
