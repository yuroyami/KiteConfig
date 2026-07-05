package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IosTaskMatchingTest {

    @Test
    fun `matches CocoaPods, plain framework, embed, and XCFramework tasks`() {
        listOf(
            "linkPodReleaseFrameworkIosArm64",
            "linkPodDebugFrameworkIosX64",
            "linkReleaseFrameworkIosArm64",
            "linkDebugFrameworkIosSimulatorArm64",
            "embedAndSignAppleFrameworkForXcode",
            "assembleSharedReleaseXCFramework",
        ).forEach { assertTrue(isIosFrameworkLinkTaskName(it), it) }
    }

    @Test
    fun `does not match non-iOS or non-link tasks`() {
        listOf(
            "linkReleaseFrameworkMacosArm64",   // wrong platform
            "linkReleaseExecutableIosArm64",    // executable, not a framework
            "compileKotlinIosArm64",            // not a link task
            "assembleDebug",                    // Android
        ).forEach { assertFalse(isIosFrameworkLinkTaskName(it), it) }
    }
}
