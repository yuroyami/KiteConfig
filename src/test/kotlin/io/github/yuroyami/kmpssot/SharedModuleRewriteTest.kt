package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedModuleRewriteTest {

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

    // --- F9: rewrite preserves the directory prefix of a nested path. ---------
    @Test
    fun `rewrite preserves a nested directory prefix`() {
        assertEquals(
            "pod 'core', :path => '../modules/core'",
            rewritePodfileContent("pod 'shared', :path => '../modules/shared'", "shared", "core"),
        )
    }

    // --- F10: rewrite preserves the original `path:` syntax. ------------------
    @Test
    fun `rewrite preserves the path-colon syntax`() {
        assertEquals(
            "pod 'core', path: '../core'",
            rewritePodfileContent("pod 'shared', path: '../shared'", "shared", "core"),
        )
    }

    @Test
    fun `pod rewrite preserves comments quotes spacing and ignores commented declarations`() {
        val source = "# pod 'shared', :path => '../shared'\n  pod \"shared\" , path: \"../modules/shared\" # keep\n"
        val expected = "# pod 'shared', :path => '../shared'\n  pod \"core\" , path: \"../modules/core\" # keep\n"
        assertEquals(expected, rewritePodfileContent(source, "shared", "core"))
    }

    @Test
    fun `Podfile rewrite ignores declarations in heredocs and embedded documentation`() {
        val source = """
            config = <<~RUBY
            pod 'shared', :path => '../shared'
            RUBY
            =begin
            pod 'shared', :path => '../shared'
            =end
            pod 'shared', :path => '../shared'
        """.trimIndent()

        val out = rewritePodfileContent(source, "shared", "core")

        assertEquals(2, Regex("pod 'shared'").findAll(out).count(), out)
        assertEquals(1, Regex("pod 'core'").findAll(out).count(), out)
        assertEquals(1, matchingPodDeclarationCount(source, "shared"))
    }

    @Test
    fun `Podfile rewrite ignores declarations in multiline quoted and percent literals`() {
        val source = "value = \"open\npod 'shared', :path => '../shared'\nclose\"\n" +
            "value = %q{open\npod 'shared', :path => '../shared'\nclose}\n" +
            "pod 'shared', :path => '../shared'"

        val out = rewritePodfileContent(source, "shared", "core")

        assertEquals(2, Regex("pod 'shared'").findAll(out).count(), out)
        assertEquals(1, Regex("pod 'core'").findAll(out).count(), out)
    }

    @Test
    fun `Podfile rewrite handles a multiline regex after a Ruby keyword`() {
        val source = "return /\npod 'shared'\n/x\n" +
            "pod 'shared', :path => '../shared'"

        val out = rewritePodfileContent(source, "shared", "core")

        assertEquals(1, Regex("pod 'shared'").findAll(out).count(), out)
        assertEquals(1, Regex("pod 'core'").findAll(out).count(), out)
    }

    @Test
    fun `Podfile scan remains linear on slash-heavy executable lines`() {
        val divisions = buildString {
            append("value = 1")
            repeat(20_000) { append(" / 2") }
        }
        val source = "$divisions\npod 'shared', :path => '../shared'"

        val out = rewritePodfileContent(source, "shared", "core")

        assertTrue(out.endsWith("pod 'core', :path => '../core'"), out.takeLast(100))
    }

    @Test
    fun `unterminated Podfile heredoc aborts the rewrite`() {
        assertThrows(IllegalArgumentException::class.java) {
            rewritePodfileContent("value = <<~RUBY\npod 'shared', :path => '../shared'", "shared", "core")
        }
    }

    @Test
    fun `Swift rewrite ignores imports inside comments and strings`() {
        val source = "/*\nimport shared\n*/\n" +
            "let text = \"\"\"\nimport shared\n\"\"\"\n" +
            "// import shared\nimport shared"
        val out = rewriteSwiftImport(source, "shared", "core")
        assertEquals(3, Regex("import shared").findAll(out).count(), out)
        assertEquals(1, Regex("import core").findAll(out).count(), out)
    }

    @Test
    fun `invalid Swift module identifier is rejected`() {
        val failure = runCatching { rewriteSwiftImport("import shared\n", "shared", "new-module") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("import shared\n", "shared", "x".repeat(256))
        }
    }

    @Test
    fun `matching declaration count exposes an ambiguous repeated pod`() {
        val podfile = "pod 'shared', :path => '../shared'\npod 'shared', path: '../modules/shared'"
        assertEquals(2, matchingPodDeclarationCount(podfile, "shared"))
    }

    @Test
    fun `escaped triple quote does not end a normal multiline Swift string`() {
        val triple = "\"\"\""
        val source = "let text = $triple\nescaped \\$triple\nimport shared\n$triple\nimport shared"

        val out = rewriteSwiftImport(source, "shared", "core")

        assertEquals(1, Regex("import shared").findAll(out).count(), out)
        assertEquals(1, Regex("import core").findAll(out).count(), out)
    }

    @Test
    fun `escaped quote does not end an extended multiline Swift string`() {
        val triple = "\"\"\""
        val source = "let text = #$triple\nescaped \\#$triple#\nimport shared\n$triple#\nimport shared"

        val out = rewriteSwiftImport(source, "shared", "core")

        assertEquals(1, Regex("import shared").findAll(out).count(), out)
        assertEquals(1, Regex("import core").findAll(out).count(), out)
    }

    @Test
    fun `Swift rewrite ignores import-looking content in an extended multiline regex`() {
        val source = "let expression = #/\nimport shared\n/#\nimport shared"

        val out = rewriteSwiftImport(source, "shared", "core")

        assertEquals("let expression = #/\nimport shared\n/#\nimport core", out)
    }

    @Test
    fun `unterminated Swift extended regex aborts the rewrite`() {
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("let expression = #/\nimport shared", "shared", "core")
        }
    }

    @Test
    fun `unterminated Swift block comment aborts the rewrite`() {
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("/* nested /* comment */\nimport shared", "shared", "core")
        }
    }

    @Test
    fun `unterminated Swift string aborts the rewrite`() {
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("let value = \"unterminated\nimport shared", "shared", "core")
        }
    }

    @Test
    fun `Swift raw-string delimiter complexity is bounded`() {
        val hashes = "#".repeat(257)
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("let value = ${hashes}\"text\"$hashes\nimport shared", "shared", "core")
        }
    }

    @Test
    fun `Swift extended-regex delimiter complexity is bounded`() {
        val hashes = "#".repeat(257)
        assertThrows(IllegalArgumentException::class.java) {
            rewriteSwiftImport("let value = ${hashes}/text/${hashes}\nimport shared", "shared", "core")
        }
    }

}
