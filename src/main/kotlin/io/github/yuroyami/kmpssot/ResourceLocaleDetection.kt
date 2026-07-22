package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_COMPOSE_RESOURCE_ENTRIES = 10_000
private val COMPOSE_LANGUAGE_QUALIFIER = Regex("[A-Za-z]{2,3}")
private val COMPOSE_REGION_QUALIFIER = Regex("[A-Za-z]{2,3}-r[A-Za-z]{2}")
private val COMPOSE_BCP47_QUALIFIER = Regex("b\\+[A-Za-z0-9]+(?:\\+[A-Za-z0-9]+)*")

/**
 * Detects immediate locale-only `values-*` resource directories without following
 * links. Accepted suffixes use Compose/Android resource syntax: `en`, `pt-rBR`,
 * or `b+sr+Latn`. Direct hyphenated BCP-47 and mixed resource qualifiers are not
 * auto-detected; users can express the former explicitly through the root DSL.
 *
 * The scan is deliberately shallow and bounded: locale conventions must not turn
 * project configuration into an unbounded source-tree traversal or read outside a
 * linked resource tree. Unsafe entries are ignored because explicit [locales][KmpSsotExtension.locales]
 * remain the authoritative escape hatch.
 */
internal fun detectComposeResourceLocales(
    composeResources: File,
    maximumEntries: Int = MAX_COMPOSE_RESOURCE_ENTRIES,
): List<String> {
    require(maximumEntries > 0) { "maximumEntries must be positive" }
    val root = composeResources.toPath()
    if (!Files.exists(root, NOFOLLOW_LINKS)) return emptyList()
    val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (rootAttributes.isSymbolicLink || !rootAttributes.isDirectory) return emptyList()

    val locales = linkedSetOf<String>()
    var entryCount = 0
    Files.newDirectoryStream(root).use { entries ->
        for (entry in entries) {
            entryCount += 1
            if (entryCount > maximumEntries) {
                throw GradleException(
                    "[kmpSsot] Refusing to inspect more than $maximumEntries Compose resource entries " +
                        "below ${diagnosticSafeText(composeResources.path)}; configure kmpSsot.locales explicitly.",
                )
            }
            val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || !attributes.isDirectory) continue
            val name = entry.fileName.toString()
            if (!name.startsWith("values-")) continue
            composeLocaleDirectoryTag(name.removePrefix("values-"))?.let(locales::add)
        }
    }
    return locales.sorted()
}

/** Canonicalize only an exact locale resource qualifier, never a mixed qualifier list. */
private fun composeLocaleDirectoryTag(suffix: String): String? {
    if (
        !COMPOSE_LANGUAGE_QUALIFIER.matches(suffix) &&
        !COMPOSE_REGION_QUALIFIER.matches(suffix) &&
        !COMPOSE_BCP47_QUALIFIER.matches(suffix)
    ) {
        return null
    }
    return canonicalLocaleTag(suffix)
}
