package io.github.yuroyami.kitessot

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end coverage against the real Android Gradle plugin at both ends of
 * kitessot's documented compatibility range.
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
                appName = 'Gradle Floor App'
                version = '1.2.3'
                appId = 'dev.matrix.gradlefloor'
            }

            tasks.register('verifyGradleFloor') {
                doLast {
                    assert kiteSsot.appName.get() == 'Gradle Floor App'
                    assert kiteSsot.version.get() == '1.2.3'
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
                appName = 'KMP Native Android Fixture'
                version = '3.4.5'
                appId = 'dev.matrix.kmpnative'
                android {
                    compileSdk = 35
                    minSdk = 24
                }
            }

            tasks.register('verifyKmpNativeAndroid') {
                dependsOn ':shared:assertSsotKmpNativeAndroid'
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
                'assertSsotKmpNativeAndroid',
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
                appName = 'Matrix App'
                version = '2.3.4'
                appId = 'dev.matrix.ssot'
                locales.addAll(['en', 'pt-BR'])
                jvmTarget = 17
                modules {
                    androidApps(':app')
                }
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                    filterResourcesToLocales = true
                }
            }

            tasks.register('verifySsotAndroidMatrix') {
                dependsOn ':app:assertSsotApplication', ':secondaryApp:assertGlobalAndroidValues', ':library:assertSsotLibrary'
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

            tasks.register('assertSsotApplication') {
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

            tasks.register('assertSsotLibrary') {
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
            .withArguments("--stacktrace", "verifySsotAndroidMatrix")
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
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

            import fixture.generated.BuildConfig

            val apiOrigin: String = BuildConfig.API_ORIGIN
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

    private fun assertGeneratedBuildConfig() {
        val generated = File(
            projectDir,
            "shared/build/generated/kitessot/commonMain/kotlin/fixture/generated/BuildConfig.kt",
        )
        assertTrue(generated.isFile, "Generated BuildConfig is missing: $generated")
        assertTrue(generated.readText().contains("API_ORIGIN"), generated.readText())
    }

    /**
     * Publish only what a real consumer receives: the plugin JAR, its minimal
     * POM, and the Gradle plugin marker. `withPluginClasspath()` is avoided on
     * purpose because TestKit injects that classpath in a scope separate from
     * externally resolved AGP plugins, which cannot exercise typed linkage.
     */
    private fun publishPluginFixture() {
        val sourceJar = File(
            requireNotNull(System.getProperty("kitessot.test.pluginJar")) {
                "agpCompatibilityTest did not provide the plugin JAR path"
            },
        )
        check(sourceJar.isFile) { "Plugin JAR does not exist: $sourceJar" }

        val mainBase = "plugin-repository/io/github/yuroyami/kitessot/test-fixture"
        val markerBase =
            "plugin-repository/io/github/yuroyami/kitessot/io.github.yuroyami.kitessot.gradle.plugin/test-fixture"
        val publishedJar = File(projectDir, "$mainBase/kitessot-test-fixture.jar")
        publishedJar.parentFile.mkdirs()
        sourceJar.copyTo(publishedJar, overwrite = true)
        write(
            "$mainBase/kitessot-test-fixture.pom",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.github.yuroyami</groupId>
              <artifactId>kitessot</artifactId>
              <version>test-fixture</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent(),
        )
        write(
            "$markerBase/io.github.yuroyami.kitessot.gradle.plugin-test-fixture.pom",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.github.yuroyami.kitessot</groupId>
              <artifactId>io.github.yuroyami.kitessot.gradle.plugin</artifactId>
              <version>test-fixture</version>
              <packaging>pom</packaging>
              <dependencies>
                <dependency>
                  <groupId>io.github.yuroyami</groupId>
                  <artifactId>kitessot</artifactId>
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
                appName = 'LibraryOnly'
                version = '1.4.150'
                propagate { version = false }
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
    fun `AGP 9_3_1 receives SDK levels from the SSOT alone and still runs the diagnostic tasks`() {
        writeSsotOnlySdkLevelsFixture()

        // A regular task: the wiring supplies compileSdk/minSdk/targetSdk from nothing.
        val supplied = runSsotOnlyFixture("verifySsotSuppliedSdkLevels")
        assertTrue(supplied.output.contains("SSOT_SUPPLIED_SDK_OK"), supplied.output)

        // The diagnostic tasks used to skip the wiring, so AGP failed configuration
        // ("does not specify compileSdk") before the diagnostic could report anything.
        val verify = runSsotOnlyFixture("kiteSsotVerify")
        assertTrue(Regex("""compileSdk\s+= 35""").containsMatchIn(verify.output), verify.output)

        val doctor = runSsotOnlyFixture("kiteSsotDoctor")
        assertTrue(doctor.output.contains("Doctor report"), doctor.output)
    }

    @Test
    fun `kiteSsotDoctor survives a version its scheme cannot encode while the SSOT supplies compileSdk`() {
        // patch 150 exceeds the default scheme's 0..99 budget, so the versionCode
        // provider throws when the wiring resolves it. On a diagnostic invocation
        // that failure must be skipped, not fatal: the SDK levels still reach AGP
        // (or configuration dies for want of compileSdk) and the doctor reports
        // the version problem as a finding.
        writeSsotOnlySdkLevelsFixture(version = "1.4.150")

        val doctor = runSsotOnlyFixture("kiteSsotDoctor")
        assertTrue(doctor.output.contains("Doctor report"), doctor.output)
        assertTrue(doctor.output.contains("KMPS050"), doctor.output)
    }

    private fun runSsotOnlyFixture(task: String): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir)
        .withGradleVersion("9.5.1")
        .withArguments("--stacktrace", task)
        .build()

    /** An application module that declares no SDK level anywhere: the SSOT is the only source. */
    private fun writeSsotOnlySdkLevelsFixture(version: String = "2.3.4") {
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
                id 'io.github.yuroyami.kitessot' version 'test-fixture'
            }

            kiteSsot {
                appName = 'Ssot Only App'
                version = '$version'
                appId = 'dev.matrix.ssotonly'
                android {
                    compileSdk = 35
                    minSdk = 24
                    targetSdk = 35
                }
            }

            tasks.register('verifySsotSuppliedSdkLevels') {
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
                    println 'SSOT_SUPPLIED_SDK_OK'
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

    /** The matrix fixture seeds module values the SSOT replaces; every adapter must say so. */
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
}
