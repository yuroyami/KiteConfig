package io.github.yuroyami.kmpssot

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RewriteSafetyTest {

    private val log = Logging.getLogger("test")

    // --- F4: backup is write-once — the earliest pristine copy survives later
    // (possibly already-modified) writes. -------------------------------------
    @Test
    fun `backup is write-once and keeps the first pristine copy`(@TempDir dir: File) {
        val f = File(dir, "project.pbxproj").apply { writeText("ORIGINAL") }

        writeTextSafely(f, "EDIT1", backup = true, dryRun = false, logger = log, label = "x")
        writeTextSafely(f, "EDIT2", backup = true, dryRun = false, logger = log, label = "x")

        assertEquals("ORIGINAL", File(f.path + BACKUP_SUFFIX).readText())
        assertEquals("EDIT2", f.readText())
    }

    // --- F1: write goes through a temp file and lands atomically, leaving no
    // stray temp behind. ------------------------------------------------------
    @Test
    fun `atomic write replaces content and leaves no temp file`(@TempDir dir: File) {
        val f = File(dir, "a.txt")
        writeTextSafely(f, "hello", backup = false, dryRun = false, logger = log, label = "x")

        assertEquals("hello", f.readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, dir.listFiles()!!.joinToString())
    }

    @Test
    fun `identical content is a no-op and writes no backup`(@TempDir dir: File) {
        val f = File(dir, "a.txt").apply { writeText("same") }
        val wrote = writeTextSafely(f, "same", backup = true, dryRun = false, logger = log, label = "x")

        assertEquals(false, wrote)
        assertTrue(!File(f.path + BACKUP_SUFFIX).exists())
    }
}
