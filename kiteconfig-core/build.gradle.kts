import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// No version: kotlin-dsl in the root project already loaded this plugin,
// and Gradle refuses a version that it cannot check.
plugins {
    id("org.gradle.kotlin.embedded-kotlin")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    // Every public declaration states its visibility and its return type, so the
    // shared surface stays deliberate.
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }

    compilerOptions {
        // These classes ship inside the plugin jar, so they follow its rules:
        // the Kotlin 2.2 level that kotlin-dsl sets, and Java 17 bytecode.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("io.github.yuroyami.kiteconfig.core.InternalKiteCoreApi")
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

tasks.named("checkKotlinAbi") {
    mustRunAfter("updateKotlinAbi")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// The release SBOM describes the plugin jar. The core ships inside that jar,
// so it is not a dependency and must not appear as a component.
tasks.named { it == "cyclonedxDirectBom" }.configureEach { enabled = false }
