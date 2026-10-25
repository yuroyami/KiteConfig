package io.github.yuroyami.kiteconfig.toolchain

import kotlin.test.Asserter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinVersionTest {

    // Kotlin Toolchain 0.13.0 compiles a module that sets no Kotlin version with Kotlin 2.4.20.
    // kotlin-test has the Kotlin version of the module. KotlinVersion.CURRENT reads the toolchain's own stdlib.
    @Test
    fun `the module compiles with kotlin 2_4_21 or newer`() {
        val version = assertNotNull(Asserter::class.java.`package`.implementationVersion)
        val (major, minor, patch) = assertNotNull(Regex("""(\d+)\.(\d+)\.(\d+)""").find(version)).destructured
        assertTrue(
            KotlinVersion(major.toInt(), minor.toInt(), patch.toInt()).isAtLeast(2, 4, 21),
            "The plugin module compiles with Kotlin $version. " +
                "Set settings: kotlin: version: 2.4.21 or newer in its module.yaml.",
        )
    }
}
