package io.github.yuroyami.kiteconfig

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every provider resolution names one DSL value, so its diagnostic id must be
 * unique. Two resolutions sharing an id cannot say which value failed, and the
 * SARIF report keys its rules by id, so one id covering two subjects produces
 * an ambiguous rule.
 */
class DiagnosticIdUniquenessTest {

    private val resolveId = Regex("""resolve(?:<[^>]*>)?\("(KTCNFG\d{3})"""")

    @Test
    fun `no two provider resolutions share a diagnostic id`() {
        val roots = listOf(File("src/main/kotlin"), File("kiteconfig-core/src/main/kotlin"))
        roots.forEach { assertTrue(it.isDirectory, "expected to run from the project root, got ${File(".").absolutePath}") }

        val sources = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
        assertTrue(sources.any { it.path.startsWith("kiteconfig-core") }, "the core sources were not scanned")

        val seen = mutableMapOf<String, MutableList<String>>()
        sources
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    resolveId.findAll(line).forEach { match ->
                        seen.getOrPut(match.groupValues[1]) { mutableListOf() }
                            .add("${file.name}:${index + 1}")
                    }
                }
            }

        val duplicates = seen.filterValues { it.size > 1 }
            .map { (id, sites) -> "$id used at ${sites.joinToString(", ")}" }
            .sorted()

        assertTrue(duplicates.isEmpty(), duplicates.joinToString("\n"))
    }
}
