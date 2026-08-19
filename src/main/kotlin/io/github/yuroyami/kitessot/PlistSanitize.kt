package io.github.yuroyami.kitessot

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Policy for an existing plist key whose value differs from the requested SSOT value.
 *
 * | Value | Existing value | Build |
 * |---|---|---|
 * | [FAIL] | untouched | fails, and the whole plist plan is abandoned |
 * | [KEEP] | kept | continues, with a warning naming the key |
 * | [REPLACE] | overwritten | continues |
 *
 * Default: [FAIL], so nothing you did not agree to lose is ever replaced.
 *
 * @see KiteSsotIosSyncExtension.onConflict for where this is selected.
 */
enum class PlistConflictPolicy {
    /** Abort the entire plist plan and leave the file byte-for-byte untouched. */
    FAIL,
    /** Preserve the existing value and report a warning. */
    KEEP,
    /** Replace the existing value with the requested value. */
    REPLACE,
}

internal data class PlistStringEntry(val name: String, val value: String)
internal data class PlistBoolEntry(val name: String, val value: Boolean)

internal data class PlistSanitizeResult(
    /** New plist text, or null when nothing changed or the plan failed. */
    val text: String?,
    val inserted: List<String>,
    val overwritten: List<String>,
    val warnings: List<String>,
    val errors: List<String> = emptyList(),
)

private const val MAX_PLIST_BYTES = 4 * 1024 * 1024
private val PLIST_VALUE_TAGS = setOf("array", "data", "date", "dict", "false", "integer", "real", "string", "true")

/**
 * Plan an XML Info.plist change using hardened XML parsing and an explicit
 * conflict policy. Any parser-hardening failure, malformed dictionary, duplicate
 * key, non-lossless baseline round-trip, or FAIL conflict aborts the complete
 * plan. No partially mutated text is returned.
 *
 * Binary/OpenStep plists are intentionally rejected by this pure migration path;
 * callers should configure generated plist/build settings instead of converting
 * a user-owned file implicitly.
 */
internal fun sanitizeInfoPlist(
    xml: String,
    stringEntries: List<PlistStringEntry>,
    boolEntries: List<PlistBoolEntry>,
    conflictPolicy: PlistConflictPolicy = PlistConflictPolicy.FAIL,
): PlistSanitizeResult {
    if (stringEntries.isEmpty() && boolEntries.isEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), emptyList())
    }
    // Fast-reject oversized ASCII before allocating an encoded copy. When the
    // UTF-16 length is within the budget, the temporary UTF-8 array is bounded
    // to a small multiple of 4 MiB and gives the actual on-disk measurement.
    if (xml.length > MAX_PLIST_BYTES || xml.toByteArray(Charsets.UTF_8).size > MAX_PLIST_BYTES) {
        return plistFailure("Info.plist exceeds the 4 MiB UTF-8 in-place migration limit")
    }
    if (!xml.trimStart().startsWith("<?xml") && !xml.trimStart().startsWith("<plist")) {
        return plistFailure("Info.plist is not an XML property list; binary/OpenStep plists are unsupported by this migration")
    }
    if (containsInternalSubset(xml) || Regex("<!ENTITY\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
        return plistFailure("Info.plist contains an internal DTD subset/entity declaration; refusing unsafe XML")
    }
    val requestedNames = (stringEntries.map { it.name } + boolEntries.map { it.name })
    val duplicateRequests = requestedNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateRequests.isNotEmpty()) return plistFailure("duplicate requested plist key(s): ${duplicateRequests.joinToString()}")

    val doc = parsePlist(xml).getOrElse { return plistFailure(it.message ?: "Info.plist XML parser setup failed") }
    val plist = doc.documentElement
    if (plist == null || plist.tagName != "plist") return plistFailure("Info.plist root element must be <plist>")
    val plistChildren = childElements(plist)
    if (plistChildren.size != 1 || plistChildren.single().tagName != "dict") {
        return plistFailure("Info.plist must contain exactly one root <dict>")
    }
    val rootDict = plistChildren.single()
    val scan = scanRootDict(rootDict)
    if (scan.errors.isNotEmpty()) return PlistSanitizeResult(null, emptyList(), emptyList(), emptyList(), scan.errors)

    // Refuse a rewrite when this JAXP implementation cannot round-trip the
    // untouched document exactly. This prevents unrelated CDATA/entity/formatting
    // normalization from hitchhiking on a one-key migration.
    val baseline = runCatching { normalizeSerialized(serializePlist(doc), xml) }.getOrElse {
        return plistFailure("Info.plist could not be serialized safely (${it.message})")
    }
    if (baseline != xml) {
        return plistFailure(
            "Info.plist cannot be round-tripped byte-for-byte by the XML migration; " +
                "use generated build settings/xcconfig or normalize it explicitly first"
        )
    }

    val indent = detectIndentUnit(rootDict)
    val inserted = mutableListOf<String>()
    val overwritten = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    fun conflict(name: String, current: Element, expected: String, replacement: () -> Unit) {
        val diagnosticName = plistDiagnosticText(name)
        when (conflictPolicy) {
            PlistConflictPolicy.FAIL -> errors +=
                "Info.plist key '$diagnosticName' is <${current.tagName}> " +
                    "'${plistDiagnosticText(current.textContent)}' but expected ${plistDiagnosticText(expected)}"
            PlistConflictPolicy.KEEP -> warnings +=
                "Info.plist key '$diagnosticName' differs from ${plistDiagnosticText(expected)}; " +
                    "preserved by conflictPolicy=KEEP"
            PlistConflictPolicy.REPLACE -> {
                replacement()
                overwritten += name
            }
        }
    }

    for (entry in stringEntries) {
        val value = scan.entries[entry.name]
        when {
            value == null -> {
                appendKeyValue(rootDict, entry.name, doc.createElement("string").apply { textContent = entry.value }, indent)
                inserted += entry.name
            }
            value.tagName == "string" && value.textContent == entry.value -> Unit
            else -> conflict(entry.name, value, "<string>${entry.value}</string>") {
                rootDict.replaceChild(doc.createElement("string").apply { textContent = entry.value }, value)
            }
        }
    }

    for (entry in boolEntries) {
        val desiredTag = if (entry.value) "true" else "false"
        val value = scan.entries[entry.name]
        when {
            value == null -> {
                appendKeyValue(rootDict, entry.name, doc.createElement(desiredTag), indent)
                inserted += entry.name
            }
            value.tagName == desiredTag -> Unit
            else -> conflict(entry.name, value, "<$desiredTag/>") {
                rootDict.replaceChild(doc.createElement(desiredTag), value)
            }
        }
    }

    if (errors.isNotEmpty()) return PlistSanitizeResult(null, emptyList(), emptyList(), warnings, errors)
    if (inserted.isEmpty() && overwritten.isEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    }

    val serialized = runCatching { normalizeSerialized(serializePlist(doc), xml) }.getOrElse {
        return PlistSanitizeResult(
            null, emptyList(), emptyList(), warnings,
            listOf("Info.plist could not be serialized safely (${it.message})"),
        )
    }
    if (serialized == xml) return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    return PlistSanitizeResult(serialized, inserted, overwritten, warnings)
}

private fun plistFailure(message: String) =
    PlistSanitizeResult(null, emptyList(), emptyList(), emptyList(), listOf(message))

private fun containsInternalSubset(xml: String): Boolean {
    val start = xml.indexOf("<!DOCTYPE", ignoreCase = true)
    if (start < 0) return false
    var quote: Char? = null
    var i = start + 9
    while (i < xml.length) {
        val c = xml[i]
        if (quote != null) {
            if (c == quote) quote = null
        } else when (c) {
            '\'', '"' -> quote = c
            '[' -> return true
            '>' -> return false
        }
        i++
    }
    return true // unterminated declaration is malformed; fail before parser work
}

private fun parsePlist(xml: String): Result<Document> = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
    builder.parse(InputSource(StringReader(xml)))
}

private data class DictScan(val entries: LinkedHashMap<String, Element>, val errors: List<String>)

private fun scanRootDict(dict: Element): DictScan {
    val elements = childElements(dict)
    val entries = LinkedHashMap<String, Element>()
    val errors = mutableListOf<String>()
    if (elements.size % 2 != 0) errors += "Info.plist root dictionary has an unpaired key/value element"
    var i = 0
    while (i < elements.size) {
        val key = elements[i]
        if (key.tagName != "key") {
            errors += "Info.plist root dictionary expected <key> but found <${key.tagName}> at element ${i + 1}"
            i++
            continue
        }
        if (childElements(key).isNotEmpty()) {
            errors += "Info.plist root dictionary <key> at element ${i + 1} must contain text only"
        }
        // Plist key text is data, not formatting. Trimming would alias
        // `<key> CFBundleName </key>` to the required `CFBundleName` key and
        // could rewrite the wrong entry while leaving the real key absent.
        val name = key.textContent
        if (name.isBlank()) errors += "Info.plist contains a blank root dictionary key"
        val value = elements.getOrNull(i + 1)
        if (value == null || value.tagName == "key") {
            errors += "Info.plist key '${plistDiagnosticText(name)}' has no following value element"
            i++
            continue
        }
        if (value.tagName !in PLIST_VALUE_TAGS) {
            errors += "Info.plist key '${plistDiagnosticText(name)}' has unsupported <${value.tagName}> value"
        } else {
            when (value.tagName) {
                "string" -> if (childElements(value).isNotEmpty()) {
                    errors += "Info.plist key '${plistDiagnosticText(name)}' has a <string> value containing nested elements"
                }
                "true", "false" -> if (value.hasChildNodes()) {
                    errors += "Info.plist key '${plistDiagnosticText(name)}' has a non-empty <${value.tagName}> value"
                }
            }
        }
        if (entries.put(name, value) != null) {
            errors += "Info.plist contains duplicate root key '${plistDiagnosticText(name)}'"
        }
        i += 2
    }
    return DictScan(entries, errors.distinct())
}

/** Keep malformed source content from flooding logs/reports or emitting controls. */
private fun plistDiagnosticText(value: String, maximumChars: Int = 160): String {
    val abbreviated = if (value.length > maximumChars) value.take(maximumChars) + "…" else value
    return buildString(abbreviated.length) {
        abbreviated.forEach { character ->
            when (character) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) {
                    append("\\u%04X".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}

private fun childElements(node: Node): List<Element> {
    val out = ArrayList<Element>()
    val kids = node.childNodes
    for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(out::add)
    return out
}

private fun detectIndentUnit(dict: Element): String {
    val kids = dict.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "key") {
            val previous = n.previousSibling
            if (previous != null && previous.nodeType == Node.TEXT_NODE) {
                val text = previous.textContent
                val newline = text.lastIndexOf('\n')
                if (newline >= 0) return text.substring(newline + 1)
            }
        }
    }
    return "\t"
}

private fun appendKeyValue(dict: Element, name: String, value: Element, indent: String) {
    val doc = dict.ownerDocument
    val last = dict.lastChild
    if (last != null && last.nodeType == Node.TEXT_NODE && last.textContent.isBlank()) dict.removeChild(last)
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(doc.createElement("key").apply { textContent = name })
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(value)
    dict.appendChild(doc.createTextNode("\n"))
}

private fun normalizeSerialized(serialized: String, original: String): String {
    var result = serialized
    if (!Regex("""<\?xml[^>]*\bstandalone\b""").containsMatchIn(original)) {
        result = result.replace(Regex(""" standalone=["']no["']"""), "")
    }
    result = result.replace(Regex("""\?>\s*<!DOCTYPE"""), "?>\n<!DOCTYPE")
    result = result.replace(Regex("""\?>\s*<plist"""), "?>\n<plist")
    result = result.replace(Regex("""(<!DOCTYPE[^>]*>)\s*<plist"""), "$1\n<plist")
    return when {
        original.endsWith("\n") && !result.endsWith("\n") -> "$result\n"
        !original.endsWith("\n") && result.endsWith("\n") -> result.dropLast(1)
        else -> result
    }
}

private fun serializePlist(doc: Document): String {
    val factory = TransformerFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }
    val transformer = factory.newTransformer().apply {
        setOutputProperty(OutputKeys.METHOD, "xml")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.INDENT, "no")
        doc.doctype?.let { doctype ->
            doctype.publicId?.let { setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, it) }
            doctype.systemId?.let { setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, it) }
        }
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    // The serializer expands '\n' to the platform line separator (CRLF on
    // Windows). XML parsing already normalized the source's line ends to '\n'
    // before the DOM existed, so any raw CR here is serializer-introduced;
    // normalize it away to keep the round-trip comparison platform-invariant.
    return writer.toString().replace("\r\n", "\n")
}
