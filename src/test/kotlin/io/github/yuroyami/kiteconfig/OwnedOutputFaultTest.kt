package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What happens when a write fails partway through.
 *
 * Reading the code cannot tell you this. The batch has to be interrupted at
 * each step and the tree inspected afterwards, because the promise is not "it
 * usually works" but "the tree is either as it was or as you asked for, never
 * half of each".
 */
class OwnedOutputFaultTest {

    @TempDir
    lateinit var projectDir: File

    private fun install(
        files: Map<String, ByteArray>,
        failAt: Int? = null,
    ) {
        val root = projectDir.resolve("res").also { it.mkdirs() }
        OwnedOutputSafety.replaceInstalledFiles(
            installationRoot = root,
            manifestFile = projectDir.resolve(".kiteconfig/owned-files-v1"),
            projectRoot = projectDir,
            owner = "fault-test",
            files = files,
            beforeMutation = { index, _ ->
                if (index == failAt) throw GradleException("injected failure at step $index")
            },
        )
    }

    private fun bytes(text: String) = text.toByteArray()

    private fun installed(name: String) = projectDir.resolve("res/$name")

    private val two = linkedMapOf("one.txt" to bytes("first"), "two.txt" to bytes("second"))

    @Test
    fun `a first-write failure leaves nothing behind`() {
        assertThrows(GradleException::class.java) { install(two, failAt = 0) }

        assertFalse(installed("one.txt").exists())
        assertFalse(installed("two.txt").exists())
        assertFalse(projectDir.resolve(".kiteconfig/owned-files-v1").exists(), "a receipt outlived its files")
    }

    @Test
    fun `a later failure rolls the earlier writes back`() {
        assertThrows(GradleException::class.java) { install(two, failAt = 1) }

        assertFalse(installed("one.txt").exists(), "the first write survived a failed batch")
        assertFalse(installed("two.txt").exists())
        assertFalse(projectDir.resolve(".kiteconfig/owned-files-v1").exists())
    }

    @Test
    fun `a failed update restores exactly what was there`() {
        install(two)
        assertEquals("first", installed("one.txt").readText())

        val changed = linkedMapOf("one.txt" to bytes("CHANGED"), "two.txt" to bytes("ALSO CHANGED"))
        assertThrows(GradleException::class.java) { install(changed, failAt = 1) }

        assertEquals("first", installed("one.txt").readText(), "a rolled-back file kept the new content")
        assertEquals("second", installed("two.txt").readText())
    }

    /** A removal is part of the same batch, so it has to come back too. */
    @Test
    fun `a failed shrink puts the dropped file back`() {
        install(two)

        val fewer = linkedMapOf("one.txt" to bytes("CHANGED"))
        assertThrows(GradleException::class.java) { install(fewer, failAt = 0) }

        assertEquals("first", installed("one.txt").readText())
        assertEquals("second", installed("two.txt").readText(), "the dropped file was not restored")
    }

    @Test
    fun `a batch that survives every step is committed whole`() {
        install(two)

        assertEquals("first", installed("one.txt").readText())
        assertEquals("second", installed("two.txt").readText())
        assertTrue(projectDir.resolve(".kiteconfig/owned-files-v1").isFile)
    }
}
