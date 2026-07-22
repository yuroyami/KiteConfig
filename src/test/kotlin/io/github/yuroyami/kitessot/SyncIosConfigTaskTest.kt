package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class SyncIosConfigTaskTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `Swift discovery rejects a source beyond the configured depth`() {
        val root = directory.resolve("iosApp")
        Files.createDirectories(root.resolve("level-one/level-two"))
        Files.writeString(root.resolve("level-one/Allowed.swift"), "import shared\n")
        Files.writeString(root.resolve("level-one/level-two/TooDeep.swift"), "import shared\n")

        val failure = assertThrows(GradleException::class.java) {
            discoverIosSwiftFiles(root, maximumDepth = 2, maximumEntries = 10)
        }

        assertTrue(failure.message.orEmpty().contains("maximum depth 2"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }

    @Test
    fun `Swift discovery bounds directories and non-Swift entries too`() {
        val root = directory.resolve("iosApp")
        Files.createDirectories(root.resolve("Sources"))
        Files.writeString(root.resolve("README.txt"), "not Swift\n")
        Files.writeString(root.resolve("Sources/Source.swift"), "import shared\n")

        val failure = assertThrows(GradleException::class.java) {
            discoverIosSwiftFiles(root, maximumDepth = 2, maximumEntries = 2)
        }

        assertTrue(failure.message.orEmpty().contains("maximum of 2 traversal entries"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }

    @Test
    fun `Swift discovery does not follow a directory symlink`() {
        val root = directory.resolve("iosApp")
        val outside = directory.resolve("outside")
        Files.createDirectories(root)
        Files.createDirectories(outside)
        Files.writeString(root.resolve("Local.swift"), "import shared\n")
        Files.writeString(outside.resolve("External.swift"), "import shared\n")
        val linkCreated = runCatching {
            Files.createSymbolicLink(root.resolve("linked-sources"), outside)
        }.isSuccess
        assumeTrue(linkCreated, "symbolic links are unavailable in this test environment")

        val discovered = discoverIosSwiftFiles(root, maximumDepth = 3, maximumEntries = 10)

        assertEquals(listOf("Local.swift"), discovered.map { it.fileName.toString() })
    }

    @Test
    fun `text rewrite budget counts snapshots and outputs cumulatively`() {
        val file = directory.resolve("Source.swift").toFile()
        val original = "123456".toByteArray(StandardCharsets.UTF_8)
        val snapshot = Utf8FileSnapshot(file.absoluteFile.normalize(), original, "123456")
        val exactBudget = IosTextRewriteBudget(maximumBytes = 10)
        exactBudget.recordSnapshot(snapshot, "Swift source")
        exactBudget.recordOutput("1234", "Swift source")
        exactBudget.verifyBeforeCommit(
            listOf(PlannedTextChange(file, original, "123456", "1234", "Swift source")),
        )

        val overflowingBudget = IosTextRewriteBudget(maximumBytes = 10)
        overflowingBudget.recordSnapshot(snapshot, "Swift source")
        val failure = assertThrows(GradleException::class.java) {
            overflowingBudget.recordOutput("12345", "Swift source")
        }

        assertTrue(failure.message.orEmpty().contains("cumulative 10-byte snapshot/output budget"), failure.message)
        assertTrue(failure.message.orEmpty().contains("no files were changed"), failure.message)
    }
}
