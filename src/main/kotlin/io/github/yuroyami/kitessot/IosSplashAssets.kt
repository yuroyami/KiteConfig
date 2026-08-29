package io.github.yuroyami.kitessot

import org.gradle.api.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Catalog entry that holds the splash plate color; also the `UIColorName` value. */
internal const val IOS_SPLASH_COLORSET = "KiteSplashBackground"

/** Catalog entry that holds the splash art; also the `UIImageName` value. */
internal const val IOS_SPLASH_IMAGESET = "KiteSplashImage"

internal const val IOS_SPLASH_IMAGE_FILE = "KiteSplashImage.png"
internal const val IOS_SPLASH_DARK_IMAGE_FILE = "KiteSplashImage-dark.png"

/** The Info.plist key the splash owns, plus the three entries it writes inside it. */
internal const val IOS_LAUNCH_SCREEN_KEY = "UILaunchScreen"
internal const val IOS_LAUNCH_SCREEN_COLOR_KEY = "UIColorName"
internal const val IOS_LAUNCH_SCREEN_IMAGE_KEY = "UIImageName"
internal const val IOS_LAUNCH_SCREEN_SAFE_AREA_KEY = "UIImageRespectsSafeAreaInsets"

private const val IOS_LAUNCH_STORYBOARD_KEY = "UILaunchStoryboardName"
private const val MAX_SPLASH_PLIST_BYTES = 4 * 1024 * 1024
private val SPLASH_HEX_RE = Regex("""#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{8}""")
private val SPLASH_PLIST_VALUE_TAGS =
    setOf("array", "data", "date", "dict", "false", "integer", "real", "string", "true")

// --- Asset catalog --------------------------------------------------------

/** Parse a splash plate color, naming [label] when the text is not a hex color. */
internal fun parseIosSplashColor(hex: String, label: String): Color {
    require(SPLASH_HEX_RE.matches(hex)) {
        "$label must be #RRGGBB or #AARRGGBB (Android convention, alpha first), got: " +
            diagnosticSafeText(hex, 32)
    }
    return parseLogoBackgroundColor(hex)
}

/** Every file the iOS splash installer owns under an `Assets.xcassets` directory. */
internal fun iosSplashOwnedPaths(assetsDir: File, withDarkImage: Boolean): List<File> =
    iosSplashOwnedRelativePaths(withDarkImage).map { assetsDir.resolve(it) }

/** The same owned files as catalog-relative `/` separated paths. */
internal fun iosSplashOwnedRelativePaths(withDarkImage: Boolean): List<String> = buildList {
    add("$IOS_SPLASH_COLORSET.colorset/Contents.json")
    add("$IOS_SPLASH_IMAGESET.imageset/Contents.json")
    add("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_IMAGE_FILE")
    if (withDarkImage) add("$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_DARK_IMAGE_FILE")
}

/** `KiteSplashBackground.colorset/Contents.json`; the dark entry appears only when [darkColor] is set. */
internal fun iosSplashColorsetJson(color: Color, darkColor: Color?): String = catalogJson(
    "colors",
    listOfNotNull(
        colorEntryLines(color, dark = false),
        darkColor?.let { colorEntryLines(it, dark = true) },
    ),
)

/** `KiteSplashImage.imageset/Contents.json`; the dark entry appears only when [withDarkImage] is true. */
internal fun iosSplashImagesetJson(withDarkImage: Boolean): String = catalogJson(
    "images",
    listOfNotNull(
        imageEntryLines(IOS_SPLASH_IMAGE_FILE, dark = false),
        if (withDarkImage) imageEntryLines(IOS_SPLASH_DARK_IMAGE_FILE, dark = true) else null,
    ),
)

/** Render the whole splash catalog, keyed by catalog-relative path in write order. */
internal fun renderIosSplashAssets(
    color: Color,
    darkColor: Color?,
    image: BufferedImage,
    darkImage: BufferedImage?,
): LinkedHashMap<String, ByteArray> {
    val rendered = LinkedHashMap<String, ByteArray>()
    rendered["$IOS_SPLASH_COLORSET.colorset/Contents.json"] =
        iosSplashColorsetJson(color, darkColor).toByteArray(StandardCharsets.UTF_8)
    rendered["$IOS_SPLASH_IMAGESET.imageset/Contents.json"] =
        iosSplashImagesetJson(darkImage != null).toByteArray(StandardCharsets.UTF_8)
    rendered["$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_IMAGE_FILE"] =
        encodePng(image, "iOS splash image")
    darkImage?.let {
        rendered["$IOS_SPLASH_IMAGESET.imageset/$IOS_SPLASH_DARK_IMAGE_FILE"] =
            encodePng(it, "iOS dark splash image")
    }
    return rendered
}

/** Install [rendered] under [assetsDir], returning how many files actually changed. */
internal fun writeIosSplashAssets(
    assetsDir: File,
    rendered: Map<String, ByteArray>,
    backup: Boolean,
    logger: Logger,
): Int {
    var written = 0
    rendered.forEach { (relative, bytes) ->
        val target = assetsDir.resolve(relative)
        OwnedOutputSafety.requireSafePath(target, "iOS splash asset")
        val changed = if (relative.endsWith(".json")) {
            writeTextSafely(
                target,
                String(bytes, StandardCharsets.UTF_8),
                backup,
                dryRun = false,
                logger = logger,
                label = "iOS splash $relative",
            )
        } else {
            writeBytesSafely(target, bytes, dryRun = false, logger = logger, label = "iOS splash $relative")
        }
        if (changed) written++
    }
    return written
}

private fun srgbComponent(channel: Int): String = String.format(Locale.ROOT, "%.3f", channel / 255.0)

private fun darkAppearanceLines(indent: String): List<String> = listOf(
    "$indent\"appearances\" : [",
    "$indent  {",
    "$indent    \"appearance\" : \"luminosity\",",
    "$indent    \"value\" : \"dark\"",
    "$indent  }",
    "$indent],",
)

private fun colorEntryLines(color: Color, dark: Boolean): List<String> = buildList {
    add("    {")
    if (dark) addAll(darkAppearanceLines("      "))
    add("      \"color\" : {")
    add("        \"color-space\" : \"srgb\",")
    add("        \"components\" : {")
    add("          \"alpha\" : \"${srgbComponent(color.alpha)}\",")
    add("          \"blue\" : \"${srgbComponent(color.blue)}\",")
    add("          \"green\" : \"${srgbComponent(color.green)}\",")
    add("          \"red\" : \"${srgbComponent(color.red)}\"")
    add("        }")
    add("      },")
    add("      \"idiom\" : \"universal\"")
    add("    }")
}

private fun imageEntryLines(fileName: String, dark: Boolean): List<String> = buildList {
    add("    {")
    if (dark) addAll(darkAppearanceLines("      "))
    add("      \"filename\" : \"$fileName\",")
    add("      \"idiom\" : \"universal\"")
    add("    }")
}

/** Same shape as the AppIcon catalog writer: two-space indent, spaced colons, kitessot author. */
private fun catalogJson(arrayName: String, entries: List<List<String>>): String {
    val body = mutableListOf<String>()
    entries.forEachIndexed { index, entry ->
        entry.forEachIndexed { position, line ->
            body += if (position == entry.lastIndex && index != entries.lastIndex) "$line," else line
        }
    }
    val lines = listOf("{", "  \"$arrayName\" : [") + body + listOf(
        "  ],",
        "  \"info\" : {",
        "    \"author\" : \"kitessot\",",
        "    \"version\" : 1",
        "  }",
        "}",
    )
    return lines.joinToString("\n") + "\n"
}

// --- Info.plist UILaunchScreen --------------------------------------------

/**
 * Plan the `UILaunchScreen` dictionary that points at the splash catalog entries.
 * Missing entries are inserted; an existing entry that differs follows
 * [conflictPolicy] exactly like every other managed plist key.
 */
internal fun mergeIosSplashLaunchScreen(
    xml: String,
    colorName: String = IOS_SPLASH_COLORSET,
    imageName: String = IOS_SPLASH_IMAGESET,
    conflictPolicy: PlistConflictPolicy = PlistConflictPolicy.FAIL,
): PlistSanitizeResult {
    if (xml.length > MAX_SPLASH_PLIST_BYTES || xml.toByteArray(Charsets.UTF_8).size > MAX_SPLASH_PLIST_BYTES) {
        return splashPlistFailure("Info.plist exceeds the 4 MiB UTF-8 in-place migration limit")
    }
    if (!xml.trimStart().startsWith("<?xml") && !xml.trimStart().startsWith("<plist")) {
        return splashPlistFailure(
            "Info.plist is not an XML property list; binary/OpenStep plists are unsupported by this migration",
        )
    }
    if (splashHasInternalSubset(xml) || Regex("<!ENTITY\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
        return splashPlistFailure("Info.plist contains an internal DTD subset/entity declaration; refusing unsafe XML")
    }

    val doc = splashParsePlist(xml).getOrElse {
        return splashPlistFailure(it.message ?: "Info.plist XML parser setup failed")
    }
    val plist = doc.documentElement
    if (plist == null || plist.tagName != "plist") return splashPlistFailure("Info.plist root element must be <plist>")
    val plistChildren = splashChildElements(plist)
    if (plistChildren.size != 1 || plistChildren.single().tagName != "dict") {
        return splashPlistFailure("Info.plist must contain exactly one root <dict>")
    }
    val rootDict = plistChildren.single()
    val rootScan = splashScanDict(rootDict, "root dictionary")
    if (rootScan.errors.isNotEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), emptyList(), rootScan.errors)
    }

    // Refuse a rewrite this serializer cannot reproduce byte-for-byte, so no
    // unrelated formatting change hitchhikes on the splash migration.
    val baseline = runCatching { splashNormalize(splashSerialize(doc), xml) }.getOrElse {
        return splashPlistFailure("Info.plist could not be serialized safely (${it.message})")
    }
    if (baseline != xml) {
        return splashPlistFailure(
            "Info.plist cannot be round-tripped byte-for-byte by the XML migration; " +
                "use generated build settings/xcconfig or normalize it explicitly first",
        )
    }

    val stringEntries = listOf(
        PlistStringEntry(IOS_LAUNCH_SCREEN_COLOR_KEY, colorName),
        PlistStringEntry(IOS_LAUNCH_SCREEN_IMAGE_KEY, imageName),
    )
    val boolEntries = listOf(PlistBoolEntry(IOS_LAUNCH_SCREEN_SAFE_AREA_KEY, true))
    val indent = splashDetectIndent(rootDict, "\t")
    val inserted = mutableListOf<String>()
    val overwritten = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    if (rootScan.entries.containsKey(IOS_LAUNCH_STORYBOARD_KEY)) {
        warnings += "Info.plist declares $IOS_LAUNCH_STORYBOARD_KEY, which iOS prefers over $IOS_LAUNCH_SCREEN_KEY; " +
            "remove it for the kiteSsot splash to show"
    }

    fun stringElement(text: String): Element = doc.createElement("string").apply { textContent = text }

    fun conflict(name: String, current: Element, expected: String, replacement: () -> Unit) {
        val safeName = diagnosticSafeText(name, 160)
        when (conflictPolicy) {
            PlistConflictPolicy.FAIL -> errors +=
                "Info.plist key '$safeName' is <${current.tagName}> " +
                    "'${diagnosticSafeText(current.textContent, 160)}' but expected ${diagnosticSafeText(expected, 160)}"
            PlistConflictPolicy.KEEP -> warnings +=
                "Info.plist key '$safeName' differs from ${diagnosticSafeText(expected, 160)}; " +
                    "preserved by conflictPolicy=KEEP"
            PlistConflictPolicy.REPLACE -> {
                replacement()
                overwritten += name
            }
        }
    }

    val existing = rootScan.entries[IOS_LAUNCH_SCREEN_KEY]
    when {
        existing == null -> {
            splashAppendRootEntry(
                rootDict,
                IOS_LAUNCH_SCREEN_KEY,
                splashBuildLaunchDict(doc, stringEntries, boolEntries, indent + indent, indent),
                indent,
            )
            inserted += IOS_LAUNCH_SCREEN_KEY
        }

        existing.tagName != "dict" -> conflict(IOS_LAUNCH_SCREEN_KEY, existing, "<dict>") {
            rootDict.replaceChild(
                splashBuildLaunchDict(doc, stringEntries, boolEntries, indent + indent, indent),
                existing,
            )
        }

        else -> {
            val launchScan = splashScanDict(existing, "$IOS_LAUNCH_SCREEN_KEY dictionary")
            if (launchScan.errors.isNotEmpty()) {
                return PlistSanitizeResult(null, emptyList(), emptyList(), warnings, launchScan.errors)
            }
            val childIndent = splashDetectIndent(existing, indent + indent)
            val pending = mutableListOf<Pair<String, Element>>()
            stringEntries.forEach { entry ->
                val value = launchScan.entries[entry.name]
                when {
                    value == null -> pending += entry.name to stringElement(entry.value)
                    value.tagName == "string" && value.textContent == entry.value -> Unit
                    else -> conflict(
                        "$IOS_LAUNCH_SCREEN_KEY.${entry.name}",
                        value,
                        "<string>${entry.value}</string>",
                    ) {
                        existing.replaceChild(stringElement(entry.value), value)
                    }
                }
            }
            boolEntries.forEach { entry ->
                val desiredTag = if (entry.value) "true" else "false"
                val value = launchScan.entries[entry.name]
                when {
                    value == null -> pending += entry.name to doc.createElement(desiredTag)
                    value.tagName == desiredTag -> Unit
                    else -> conflict("$IOS_LAUNCH_SCREEN_KEY.${entry.name}", value, "<$desiredTag/>") {
                        existing.replaceChild(doc.createElement(desiredTag), value)
                    }
                }
            }
            if (pending.isNotEmpty() && errors.isEmpty()) {
                val closing = splashTakeTrailingBlankText(existing) ?: "\n$indent"
                pending.forEach { (name, value) -> splashAppendEntry(existing, name, value, childIndent) }
                existing.appendChild(doc.createTextNode(closing))
                inserted += pending.map { "$IOS_LAUNCH_SCREEN_KEY.${it.first}" }
            }
        }
    }

    if (errors.isNotEmpty()) return PlistSanitizeResult(null, emptyList(), emptyList(), warnings, errors)
    if (inserted.isEmpty() && overwritten.isEmpty()) {
        return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    }
    val serialized = runCatching { splashNormalize(splashSerialize(doc), xml) }.getOrElse {
        return PlistSanitizeResult(
            null, emptyList(), emptyList(), warnings,
            listOf("Info.plist could not be serialized safely (${it.message})"),
        )
    }
    if (serialized == xml) return PlistSanitizeResult(null, emptyList(), emptyList(), warnings)
    return PlistSanitizeResult(serialized, inserted, overwritten, warnings)
}

private fun splashPlistFailure(message: String) =
    PlistSanitizeResult(null, emptyList(), emptyList(), emptyList(), listOf(message))

private data class SplashDictScan(val entries: LinkedHashMap<String, Element>, val errors: List<String>)

/** Pairwise key/value walk of one dictionary, refusing anything malformed. */
private fun splashScanDict(dict: Element, path: String): SplashDictScan {
    val elements = splashChildElements(dict)
    val entries = LinkedHashMap<String, Element>()
    val errors = mutableListOf<String>()
    if (elements.size % 2 != 0) errors += "Info.plist $path has an unpaired key/value element"
    var i = 0
    while (i < elements.size) {
        val key = elements[i]
        if (key.tagName != "key") {
            errors += "Info.plist $path expected <key> but found <${key.tagName}> at element ${i + 1}"
            i++
            continue
        }
        // Key text is data, not formatting: trimming would alias a padded key
        // onto the managed one and rewrite the wrong entry.
        val name = key.textContent
        if (name.isBlank()) errors += "Info.plist $path contains a blank key"
        val value = elements.getOrNull(i + 1)
        if (value == null || value.tagName == "key") {
            errors += "Info.plist key '${diagnosticSafeText(name, 160)}' has no following value element"
            i++
            continue
        }
        if (value.tagName !in SPLASH_PLIST_VALUE_TAGS) {
            errors += "Info.plist key '${diagnosticSafeText(name, 160)}' has unsupported <${value.tagName}> value"
        } else when (value.tagName) {
            "string" -> if (splashChildElements(value).isNotEmpty()) {
                errors += "Info.plist key '${diagnosticSafeText(name, 160)}' has a <string> value containing nested elements"
            }
            "true", "false" -> if (value.hasChildNodes()) {
                errors += "Info.plist key '${diagnosticSafeText(name, 160)}' has a non-empty <${value.tagName}> value"
            }
        }
        if (entries.put(name, value) != null) {
            errors += "Info.plist $path contains duplicate key '${diagnosticSafeText(name, 160)}'"
        }
        i += 2
    }
    return SplashDictScan(entries, errors.distinct())
}

private fun splashBuildLaunchDict(
    doc: Document,
    stringEntries: List<PlistStringEntry>,
    boolEntries: List<PlistBoolEntry>,
    childIndent: String,
    closeIndent: String,
): Element {
    val dict = doc.createElement("dict")
    stringEntries.forEach { entry ->
        val value = doc.createElement("string").apply { textContent = entry.value }
        splashAppendEntry(dict, entry.name, value, childIndent)
    }
    boolEntries.forEach { entry ->
        splashAppendEntry(dict, entry.name, doc.createElement(if (entry.value) "true" else "false"), childIndent)
    }
    dict.appendChild(doc.createTextNode("\n$closeIndent"))
    return dict
}

private fun splashAppendEntry(dict: Element, name: String, value: Element, indent: String) {
    val doc = dict.ownerDocument
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(doc.createElement("key").apply { textContent = name })
    dict.appendChild(doc.createTextNode("\n$indent"))
    dict.appendChild(value)
}

private fun splashAppendRootEntry(dict: Element, name: String, value: Element, indent: String) {
    splashTakeTrailingBlankText(dict)
    splashAppendEntry(dict, name, value, indent)
    dict.appendChild(dict.ownerDocument.createTextNode("\n"))
}

/** Detach and return the dictionary's trailing whitespace node, if it has one. */
private fun splashTakeTrailingBlankText(dict: Element): String? {
    val last = dict.lastChild ?: return null
    if (last.nodeType != Node.TEXT_NODE || !last.textContent.isBlank()) return null
    val text = last.textContent
    dict.removeChild(last)
    return text
}

private fun splashDetectIndent(dict: Element, fallback: String): String {
    val kids = dict.childNodes
    for (i in 0 until kids.length) {
        val node = kids.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == "key") {
            val previous = node.previousSibling
            if (previous != null && previous.nodeType == Node.TEXT_NODE) {
                val text = previous.textContent
                val newline = text.lastIndexOf('\n')
                if (newline >= 0) return text.substring(newline + 1)
            }
        }
    }
    return fallback
}

private fun splashChildElements(node: Node): List<Element> {
    val out = ArrayList<Element>()
    val kids = node.childNodes
    for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(out::add)
    return out
}

private fun splashHasInternalSubset(xml: String): Boolean {
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
    return true // an unterminated declaration is malformed; fail before parser work
}

private fun splashParsePlist(xml: String): Result<Document> = runCatching {
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

private fun splashSerialize(doc: Document): String {
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
    // Any raw CR here is serializer-introduced: XML parsing normalized the
    // source's line ends before the DOM existed.
    return writer.toString().replace("\r\n", "\n")
}

private fun splashNormalize(serialized: String, original: String): String {
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
