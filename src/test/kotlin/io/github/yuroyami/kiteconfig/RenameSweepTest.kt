package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No old name may survive outside the historical record.
 *
 * The exempt files are kept as written on purpose: they describe what the
 * plugin was called at the time, so rewriting them would make them lie.
 */
class RenameSweepTest {

    /** Historical documents, plus this file, which must name the old spellings. */
    private val exemptFiles = setOf(
        "CHANGELOG.md", "OVERHAUL.md", "SOLAUDIT.md", "RenameSweepTest.kt",
    )

    /**
     * `specs/` and `docs/superpowers/` hold design documents and plans for work
     * that already shipped. They record decisions as they were made, under the
     * name the plugin had at the time.
     */
    private val exemptDirs =
        listOf(
            "docs/superpowers/",
            "specs/",
            ".superpowers/",
            // Release notes describe one shipped version and name the plugin it
            // migrated from. They are frozen the moment that version is tagged.
            ".github/release-notes/",
            "build/",
            ".git/",
            ".gradle/",
        )

    private val scanned = setOf("kt", "kts", "java", "md", "yml", "yaml", "api", "ftl")

    private val oldNames = listOf(
        "kitessot", "kiteSsot", "KiteSsot", "KiteSSOT", "KITESSOT", "KITE_SSOT", "KMPS",
        // Separated spellings: a task group read "kite ssot" and survived a
        // rename pass that only looked for the joined forms.
        "kite ssot", "Kite SSOT", "kite-ssot", "kmp-ssot",
    )

    /**
     * Match whole path segments, never a raw prefix.
     *
     * `.git/` trimmed to `.git` also prefix-matches `.github`, which silently
     * excluded every workflow and issue template from the sweep.
     */
    private fun File.isExempt(root: File): Boolean {
        val rel = relativeTo(root).path.replace('\\', '/')
        if (rel.isEmpty()) return false
        return exemptDirs.any { raw ->
            val dir = raw.trimEnd('/')
            rel == dir || rel.startsWith("$dir/")
        }
    }

    private fun repoRoot(): File {
        var dir = File(".").canonicalFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repository root")
        }
        return dir
    }

    @Test
    fun `no old name survives outside the historical record`() {
        val root = repoRoot()

        val offences = root.walkTopDown()
            .onEnter { dir -> !dir.isExempt(root) }
            .filter { it.isFile && it.extension in scanned }
            .filterNot { it.name in exemptFiles }
            .flatMap { file ->
                val lines = file.readLines()
                // The README's closing history section names every old spelling
                // on purpose, so the sweep stops where that section begins.
                val limit = if (file.name == "README.md") {
                    lines.indexOfFirst { it.startsWith("## Name and version history") }
                        .takeIf { it >= 0 } ?: lines.size
                } else {
                    lines.size
                }
                lines.take(limit).withIndex().mapNotNull { (index, line) ->
                    val hit = oldNames.firstOrNull { line.contains(it) } ?: return@mapNotNull null
                    "${file.relativeTo(root).path}:${index + 1} says \"$hit\""
                }
            }
            .toList()

        assertEquals(emptyList<String>(), offences, offences.joinToString("\n"))
    }

    @Test
    fun `the sweep actually scans a meaningful number of files`() {
        val root = repoRoot()
        val scannedCount = root.walkTopDown()
            .onEnter { dir -> !dir.isExempt(root) }
            .filter { it.isFile && it.extension in scanned }
            .count()

        // Guards against a broken walk silently passing the sweep above.
        assertTrue(scannedCount > 100, "sweep only reached $scannedCount files")
    }
}
