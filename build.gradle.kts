import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "io.github.yuroyami"
version = providers.gradleProperty("kmpSsot.version").getOrElse("1.0.0")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Compile on JDK 21 (the toolchain) but emit Java-17 bytecode so a consumer whose
// Gradle daemon runs on JDK 17 — still the most common Android setup — can load
// the plugin without UnsupportedClassVersionError. Nothing here uses a 21-only API.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    // Consumers bring their own AGP / Kotlin Gradle plugin; we only need types
    // at compile time. The full KGP (not just -api) is needed for the concrete
    // KotlinMultiplatformExtension used by interop opt-in + web worker wiring.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// KGP is compileOnly, so TestKit's injected plugin classpath (built from
// runtimeClasspath) wouldn't include it — functional tests that apply
// kotlin("multiplatform") in fixtures need it added explicitly. This also puts
// KGP in the SAME classloader as the plugin under test, matching the documented
// consumer setup (kotlin declared in the root plugins block).
val testKitPluginClasspath: Configuration by configurations.creating
dependencies {
    testKitPluginClasspath(libs.kotlin.gradle.plugin)
}
tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(testKitPluginClasspath)
}

gradlePlugin {
    website = "https://github.com/yuroyami/kmp-ssot"
    vcsUrl = "https://github.com/yuroyami/kmp-ssot.git"
    plugins {
        create("kmpSsot") {
            id = "io.github.yuroyami.kmpssot"
            implementationClass = "io.github.yuroyami.kmpssot.KmpSsotPlugin"
            displayName = "KMP SSOT Plugin"
            description = "Single source of truth for KMP app configuration (appName, version, bundleId, locales, app logo, Android SDK levels) propagated to Android + iOS."
            tags = listOf("kotlin", "kotlin-multiplatform", "kmp", "android", "ios", "configuration", "versioning")
        }
    }
}

// Apache-2.0 licence metadata on every published POM.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
        }
    }
    // Keep GitHub Packages as a secondary channel (internal / pre-release builds).
    // Plugin Portal is the primary public distribution, set up by plugin-publish.
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/yuroyami/kmp-ssot")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
