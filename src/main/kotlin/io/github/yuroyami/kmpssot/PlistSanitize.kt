package io.github.yuroyami.kmpssot

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal data class PlistStringEntry(val name: String, val value: String)
internal data class PlistBoolEntry(val name: String, val value: Boolean)

internal data class PlistSanitizeResult(
    /** New plist text, or null when nothing changed / the file could not be parsed. */
    val text: String?,
    val inserted: List<String>,
    val overwritten: List<String>,
    val warnings: List<String>,
)

/**
 * Sanitize an `Info.plist` against the desired SSOT keys using a **real XML
 * parser** rather than regex. This fixes the duplicate-key bug the regex
 * version had: a key whose value is a `<dict>`, `<array>`, CDATA string, or
 * `<integer>` is
 * now correctly recognised as *present* (the old `<key>…</key><string>…` value-
 * shaped regex treated it as missing and inserted a second key).
 *
 * Rules:
 *  - String entries (the `$(…)` SSOT references) are **append-only**: inserted
 *    when the key is absent; never overwritten. If the key exists with a
 *    different value a warning is emitted so the user knows SSOT propagation
 *    won't reach that field.
 *  - Bool entries (the `ios { }` feature flags) are **DSL-wins**: inserted when
 *    absent, the value replaced when it differs. A key present with a non-bool
 *    value is left alone with a warning (we don't clobber an unexpected type).
 *
 * External DTD loading is disabled so the Apple `PropertyList-1.0.dtd` DOCTYPE
 * never triggers a network fetch. On any parse failure the function returns a
 * null [PlistSanitizeResult.text] plus a warning — it never corrupts the file.
 */
internal fun sanitizeInfoPlist(
    xml: String,
    stringEntries: List<PlistStringEntry>,
    boolEntries: List<PlistBoolEntry>,
): PlistSanitizeResult {
    if (stringEntries.isEmpty() && boolEntries.isEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), emptyList())
    }

    val doc = try {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            // Don't fetch the Apple plist DTD referenced by the DOCTYPE.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val db = dbf.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        db.parse(InputSource(StringReader(xml)))
    } catch (e: Exception) {
        return PlistSanitizeResult(
            null, emptyList(), emptyList(),
            listOf("Info.plist could not be parsed as XML (${e.message}) — left untouched."),
        )
    }

    val rootDict = doc.documentElement
        ?.let { plist -> childElements(plist).firstOrNull { it.tagName == "dict" } }
        ?: return PlistSanitizeResult(
            null, emptyList(), emptyList(),
            listOf("Info.plist has no root <dict> — left untouched."),
        )

    val pairs = rootDictPairs(rootDict)
    val indent = detectIndentUnit(rootDict)
    val inserted = mutableListOf<String>()
    val overwritten = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    for (entry in stringEntries) {
        val present = pairs.containsKey(entry.name)
        val value = pairs[entry.name]
        when {
            !present -> {
                appendKeyValue(rootDict, entry.name, doc.createElement("string").apply { textContent = entry.value }, indent)
                inserted += entry.name
            }
            value == null -> warnings += "Info.plist has a <key>${entry.name}</key> with no following value " +
                "element (malformed plist) — leaving it untouched rather than inserting a duplicate."
            value.tagName == "string" && value.textContent == entry.value -> { /* already correct */ }
            value.tagName == "string" -> warnings += "Info.plist <${entry.name}> is hardcoded to " +
                "\"${value.textContent}\" — kmpSsot propagation will NOT reach it. Change it to " +
                "<string>${entry.value}</string> to restore SSOT."
            else -> warnings += "Info.plist <${entry.name}> has an unexpected <${value.tagName}> value — " +
                "leaving it untouched. Expected <string>${entry.value}</string>."
        }
    }

    for (entry in boolEntries) {
        val present = pairs.containsKey(entry.name)
        val value = pairs[entry.name]
        val desiredTag = if (entry.value) "true" else "false"
        when {
            !present -> {
                appendKeyValue(rootDict, entry.name, doc.createElement(desiredTag), indent)
                inserted += entry.name
            }
            value == null -> warnings += "Info.plist has a <key>${entry.name}</key> with no following value " +
                "element (malformed plist) — leaving it untouched."
            value.tagName == "true" || value.tagName == "false" -> {
                if (value.tagName != desiredTag) {
                    rootDict.replaceChild(doc.createElement(desiredTag), value)
                    overwritten += entry.name
                }
            }
            else -> warnings += "Info.plist <${entry.name}> has a non-boolean <${value.tagName}> value — " +
                "leaving it untouched."
        }
    }

    if (inserted.isEmpty() && overwritten.isEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    }

    val serialized = try {
        serializePlist(doc)
    } catch (e: Exception) {
        return PlistSanitizeResult(
            null, emptyList(), emptyList(),
            warnings + "Info.plist could not be re-serialized (${e.message}) — left untouched.",
        )
    }
    val reheadered = normalizePlistProlog(serialized, xml)
    val normalized = if (xml.endsWith("\n") && !reheadered.endsWith("\n")) reheadered + "\n" else reheadered
    if (normalized == xml) return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    return PlistSanitizeResult(normalized, inserted, overwritten, warnings)
}

private fun childElements(node: Node): List<Element> {
    val out = ArrayList<Element>()
    val kids = node.childNodes
    for (i in 0 until kids.length) (kids.item(i) as? Element)?.let { out += it }
    return out
}

/**
 * Map each top-level `<key>` to its following value element, in document order.
 * A trailing `<key>` with no following element (malformed plist) is recorded
 * with a **null** value so callers can tell "key present but unpaired" apart from
 * "key absent" — and warn instead of inserting a duplicate key.
 */
private fun rootDictPairs(dict: Element): LinkedHashMap<String, Element?> {
    val els = childElements(dict)
    val map = LinkedHashMap<String, Element?>()
    var i = 0
    while (i < els.size) {
        val el = els[i]
        if (el.tagName == "key") {
            val name = el.textContent.trim()
            if (i + 1 < els.size) {
                map[name] = els[i + 1]
                i += 2
            } else {
                map[name] = null // dangling trailing <key>
                i++
            }
        } else {
            i++
        }
    }
    return map
}

/** Sniff the indentation used for entries in the root dict (tabs/spaces). */
private fun detectIndentUnit(dict: Element): String {
    val kids = dict.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "key") {
            val prev = n.previousSibling
            if (prev != null && prev.nodeType == Node.TEXT_NODE) {
                val t = prev.textContent
                val nl = t.lastIndexOf('\n')
                if (nl >= 0) return t.substring(nl + 1)
            }
            break
        }
    }
    return "\t"
}

/** Append `<key>name</key><value/>` to the end of the dict, each on its own indented line. */
private fun appendKeyValue(dict: Element, name: String, value: Element, indent: String) {
    val doc = dict.ownerDocument
    // Consume the trailing whitespace-only text node before </dict> (the original
    // one, or the "\n" a previous insert added) so back-to-back inserts don't stack
    // two newlines into a blank line.
    val last = dict.lastChild
    if (last != null && last.nodeType == Node.TEXT_NODE && last.textContent.isBlank()) {
        dict.removeChild(last)
    }
    val key = doc.createElement("key").apply { textContent = name }
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(key)
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(value)
    // Re-establish the newline that precedes </dict>.
    dict.appendChild(doc.createTextNode("\n"))
}

/**
 * Undo the JDK Transformer's prolog mangling so a one-key insert produces a
 * faithful, minimal diff on a user-owned `Info.plist`:
 *  - it injects `standalone="no"` that wasn't there, and
 *  - it collapses the newlines between the `<?xml?>` declaration, the `<!DOCTYPE>`
 *    and `<plist>` onto a single line.
 * Apple's tooling emits neither, so restore the canonical multi-line prolog.
 */
private fun normalizePlistProlog(serialized: String, original: String): String {
    var s = serialized
    // Drop standalone="no" unless the original genuinely declared standalone.
    if (!Regex("""<\?xml[^>]*\bstandalone\b""").containsMatchIn(original)) {
        s = s.replace(Regex(""" standalone=["']no["']"""), "")
    }
    // Re-expand the prolog onto separate lines (declaration / DOCTYPE / <plist>).
    s = s.replace(Regex("""\?>\s*<!DOCTYPE"""), "?>\n<!DOCTYPE")
    s = s.replace(Regex("""\?>\s*<plist"""), "?>\n<plist")
    s = s.replace(Regex("""(<!DOCTYPE[^>]*>)\s*<plist"""), "$1\n<plist")
    return s
}

private fun serializePlist(doc: org.w3c.dom.Document): String {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.METHOD, "xml")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty(OutputKeys.INDENT, "no")
        doc.doctype?.let { dt ->
            dt.publicId?.let { setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, it) }
            dt.systemId?.let { setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, it) }
        }
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    return writer.toString()
}
