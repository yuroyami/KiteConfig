package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedModuleRewriteTest {

    @Test
    fun `detects when pod name equals path tail`() {
        assertEquals("shared", detectPodSharedModule("pod 'shared', :path => '../shared'"))
    }

    @Test
    fun `returns null when pod name differs from path tail`() {
        assertNull(detectPodSharedModule("pod 'SharedKit', :path => '../shared'"))
    }

    @Test
    fun `rewrites an exact whole-module import`() {
        assertEquals("import composeApp\n", rewriteSwiftImport("import shared\n", "shared", "composeApp"))
    }

    @Test
    fun `leaves submodule, same-prefix, and testable imports alone`() {
        val src = "import shared\nimport sharedKit\nimport shared.Foo\n@testable import shared\n"
        val out = rewriteSwiftImport(src, "shared", "composeApp")
        assertTrue(out.contains("import composeApp\n"), out)
        assertTrue(out.contains("import sharedKit\n"), out)
        assertTrue(out.contains("import shared.Foo\n"), out)
        assertTrue(out.contains("@testable import shared\n"), out)
    }

    @Test
    fun `rewrites the podfile pod line`() {
        assertEquals(
            "pod 'composeApp', :path => '../composeApp'",
            rewritePodfileContent("pod 'shared', :path => '../shared'", "shared", "composeApp"),
        )
    }
}
