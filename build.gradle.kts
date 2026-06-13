import org.gradle.api.publish.maven.MavenPublication

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

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    // Consumers bring their own AGP; we only need types at compile time.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
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
