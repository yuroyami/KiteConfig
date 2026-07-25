package io.github.yuroyami.kitessot

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class RewriteSafetyTest {

    private val log = Logging.getLogger("test")

    // --- F4: backup is write-once. The earliest pristine copy survives later
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

    @Test
    fun `contained path rejects parent traversal`(@TempDir dir: File) {
        val outside = File(dir, "../outside.txt")
        assertThrows(IllegalArgumentException::class.java) {
            requireContainedPath(dir, outside, mustExist = false)
        }
    }

    @Test
    fun `contained path rejects a symlink target`(@TempDir dir: File) {
        val real = File(dir, "real.txt").apply { writeText("x") }
        val link = File(dir, "link.txt")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertThrows(IllegalArgumentException::class.java) { requireContainedPath(dir, link) }
    }

    @Test
    fun `strict text snapshot rejects an oversized sparse file without an unbounded read`(@TempDir dir: File) {
        val file = dir.resolve("oversized.txt")
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.position(MAX_REWRITE_TEXT_BYTES)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            readUtf8SnapshotStrict(file)
        }

        assertTrue(failure.message.orEmpty().contains("limit $MAX_REWRITE_TEXT_BYTES bytes"), failure.message)
    }

    @Test
    fun `batch plan writes all files and creates pristine backups`(@TempDir dir: File) {
        val first = File(dir, "first.txt").apply { writeText("one") }
        val second = File(dir, "second.txt").apply { writeText("two") }
        val plan = listOfNotNull(
            planTextChange(dir, first, "ONE", "first"),
            planTextChange(dir, second, "TWO", "second"),
        )
        val result = applyTextRewritePlan(dir, plan, backup = true, dryRun = false, logger = log)
        assertEquals(2, result.written)
        assertEquals("ONE", first.readText())
        assertEquals("TWO", second.readText())
        assertEquals("one", File(first.path + BACKUP_SUFFIX).readText())
        assertEquals("two", File(second.path + BACKUP_SUFFIX).readText())
    }

    @Test
    fun `dry run prints plan without touching target`(@TempDir dir: File) {
        val file = File(dir, "Info.plist").apply { writeText("old") }
        val plan = listOfNotNull(planTextChange(dir, file, "new", "plist"))
        val result = applyTextRewritePlan(dir, plan, backup = true, dryRun = true, logger = log)
        assertTrue(result.dryRun)
        assertEquals("old", file.readText())
        assertTrue(!File(file.path + BACKUP_SUFFIX).exists())
    }

    @Test
    fun `diff preview bounds changed regions and escapes terminal controls`(@TempDir dir: File) {
        val old = (0 until 100).joinToString("\n") { "old-$it-${"o".repeat(1_000)}" }
        val new = (0 until 100).joinToString("\n") { "new-$it-\u001B[31m${"n".repeat(1_000)}" }

        val preview = renderUnifiedDiff(dir.resolve("unsafe\u001B[2J.pbxproj"), old, new)

        assertTrue(preview.length < 50_000, preview.length.toString())
        assertTrue(preview.contains("removed line(s) omitted"), preview)
        assertTrue(preview.contains("added line(s) omitted"), preview)
        assertTrue(preview.contains("\\u001b[31m"), preview)
        assertTrue(!preview.contains('\u001B'), preview)
        assertTrue(!preview.contains("n".repeat(600)), preview)
    }

    @Test
    fun `rewrite derived from an old snapshot refuses a concurrent edit`(@TempDir dir: File) {
        val file = dir.resolve("Info.plist").apply { writeText("source A") }
        val snapshot = readUtf8SnapshotStrict(file)
        file.writeText("external B")
        val plan = listOfNotNull(planTextChange(dir, file, "derived from A", "plist", snapshot))

        assertThrows(IllegalStateException::class.java) {
            applyTextRewritePlan(dir, plan, backup = false, dryRun = false, logger = log)
        }

        assertEquals("external B", file.readText())
    }

    @Test
    fun `batch revalidates each target immediately before its move`(@TempDir dir: File) {
        val first = dir.resolve("first.txt").apply { writeText("one") }
        val second = dir.resolve("second.txt").apply { writeText("two") }
        val plan = listOfNotNull(
            planTextChange(dir, first, "ONE", "first"),
            planTextChange(dir, second, "TWO", "second"),
        )

        assertThrows(IllegalStateException::class.java) {
            applyTextRewritePlan(
                dir,
                plan,
                backup = false,
                dryRun = false,
                logger = log,
            ) { index, _ ->
                if (index == 1) second.writeText("external second")
            }
        }

        assertEquals("one", first.readText())
        assertEquals("external second", second.readText())
    }

    @Test
    fun `batch backup contains planned bytes when target changes before mutation`(@TempDir dir: File) {
        val file = dir.resolve("Info.plist").apply { writeText("planned original") }
        val plan = listOfNotNull(planTextChange(dir, file, "generated", "plist"))

        assertThrows(IllegalStateException::class.java) {
            applyTextRewritePlan(
                dir,
                plan,
                backup = true,
                dryRun = false,
                logger = log,
            ) { _, _ -> file.writeText("external edit") }
        }

        assertEquals("planned original", File(file.path + BACKUP_SUFFIX).readText())
        assertEquals("external edit", file.readText())
    }

    @Test
    fun `rollback preserves an external edit made after an earlier batch write`(@TempDir dir: File) {
        val first = dir.resolve("first.txt").apply { writeText("one") }
        val second = dir.resolve("second.txt").apply { writeText("two") }
        val plan = listOfNotNull(
            planTextChange(dir, first, "ONE", "first"),
            planTextChange(dir, second, "TWO", "second"),
        )

        assertThrows(java.io.IOException::class.java) {
            applyTextRewritePlan(
                dir,
                plan,
                backup = false,
                dryRun = false,
                logger = log,
            ) { index, _ ->
                if (index == 1) {
                    first.writeText("external after write")
                    throw java.io.IOException("injected second-write failure")
                }
            }
        }

        assertEquals("external after write", first.readText())
        assertEquals("two", second.readText())
    }
}
