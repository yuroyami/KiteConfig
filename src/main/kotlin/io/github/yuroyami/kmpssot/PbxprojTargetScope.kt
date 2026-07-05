package io.github.yuroyami.kmpssot

/**
 * Minimal, span-preserving structural reader for `project.pbxproj` — just enough
 * of the OpenStep/NeXTSTEP object graph to scope build-setting rewrites to the
 * **application target's** build configurations, so a global regex no longer
 * clobbers test / extension / widget targets (each of which legitimately has its
 * own `PRODUCT_NAME` and a distinct, dot-suffixed `PRODUCT_BUNDLE_IDENTIFIER`).
 *
 * Not a full parser: it locates each top-level object's brace-balanced span and
 * walks the id references (target → configuration list → configurations). All
 * pure and unit-tested; no Gradle, no file I/O.
 */

/** 24-hex-digit pbxproj object identifier. */
private const val ID = """[0-9A-Fa-f]{24}"""

/** Start of a top-level object entry: `<id> [/* comment */] = {`. */
private val OBJECT_START = Regex("""($ID)\s*(?:/\*.*?\*/\s*)?=\s*\{""", RegexOption.DOT_MATCHES_ALL)

/**
 * Map every top-level object id to the inclusive char span of its `{ … }` body
 * (id start … matching `}`). Braces inside quoted strings and `/* … */` comments
 * are ignored, so a build setting like `OTHER_LDFLAGS = "-Wl,{x}"` can't unbalance
 * the walk. Each object's body is skipped after it is recorded, so nested
 * `buildSettings = { … }` blocks never register as their own objects.
 */
internal fun findObjectSpans(text: String): Map<String, IntRange> {
    val out = LinkedHashMap<String, IntRange>()
    var from = 0
    while (from < text.length) {
        val m = OBJECT_START.find(text, from) ?: break
        val open = m.range.last // index of the '{' the match ends on
        val close = matchBrace(text, open)
        if (close < 0) break
        out[m.groupValues[1]] = m.range.first..close
        from = close + 1
    }
    return out
}

/** Index of the `}` matching the `{` at [openIdx], skipping strings/comments; -1 if unbalanced. */
private fun matchBrace(text: String, openIdx: Int): Int {
    var depth = 0
    var i = openIdx
    while (i < text.length) {
        when {
            text[i] == '"' -> { i = skipQuoted(text, i); continue }
            text[i] == '/' && i + 1 < text.length && text[i + 1] == '*' -> { i = skipComment(text, i); continue }
            text[i] == '{' -> depth++
            text[i] == '}' -> { depth--; if (depth == 0) return i }
        }
        i++
    }
    return -1
}

/** Index just past the closing `"` of the pbxproj string opening at [quoteIdx] (handles `\"`). */
private fun skipQuoted(text: String, quoteIdx: Int): Int {
    var i = quoteIdx + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> { i += 2; continue }
            '"' -> return i + 1
        }
        i++
    }
    return text.length
}

/** Index just past the closing `* /` of the comment opening at [startIdx]. */
private fun skipComment(text: String, startIdx: Int): Int {
    var i = startIdx + 2
    while (i + 1 < text.length) {
        if (text[i] == '*' && text[i + 1] == '/') return i + 2
        i++
    }
    return text.length
}

private val CONFIG_LIST_REF = Regex("""buildConfigurationList\s*=\s*($ID)""")
private val APP_PRODUCT_TYPE = Regex("""productType\s*=\s*"?com\.apple\.product-type\.application"?""")
private val BUILD_CONFIGS_BLOCK = Regex("""buildConfigurations\s*=\s*\(([^)]*)\)""")
private val ID_RE = Regex(ID)

/**
 * Spans of the `XCBuildConfiguration` objects owned by every **application**
 * `PBXNativeTarget` (target → `buildConfigurationList` → each `buildConfigurations`
 * id), sorted by start offset. Empty when the file has no application target
 * (a bare settings fragment, or a framework/library-only project) — callers then
 * decide whether to fall back to a global rewrite.
 */
internal fun applicationBuildConfigSpans(text: String): List<IntRange> {
    val spans = findObjectSpans(text)

    val appConfigListIds = spans.values.mapNotNull { range ->
        val body = text.substring(range)
        if (!body.contains("isa = PBXNativeTarget") || !APP_PRODUCT_TYPE.containsMatchIn(body)) return@mapNotNull null
        CONFIG_LIST_REF.find(body)?.groupValues?.get(1)
    }

    val configIds = appConfigListIds.flatMap { listId ->
        val listRange = spans[listId] ?: return@flatMap emptyList<String>()
        val inner = BUILD_CONFIGS_BLOCK.find(text.substring(listRange))?.groupValues?.get(1) ?: return@flatMap emptyList()
        ID_RE.findAll(inner).map { it.value }.toList()
    }.distinct()

    return configIds.mapNotNull { spans[it] }.sortedBy { it.first }
}
