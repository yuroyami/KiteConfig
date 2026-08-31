package io.github.yuroyami.kiteconfig

/** Pure helpers for the explicit, one-shot shared-module migration. */

private val POD_LINE = Regex(
    """(?m)^([ \t]*pod[ \t]+)(['"])([^'"\r\n]+)(['"])([ \t]*,[ \t]*(?::path[ \t]*=>|path[ \t]*:)[ \t]*)(['"])(\.\./(?:[^'"\r\n]*/)?)([^/'"\r\n]+)(['"])([ \t]*(?:#[^\r\n]*)?)$"""
)

private data class PodDeclaration(
    val match: MatchResult,
    val name: String,
    val pathTail: String,
) {
    val hasBalancedQuotes: Boolean
        get() = match.groupValues[2] == match.groupValues[4] && match.groupValues[6] == match.groupValues[9]
}

internal data class PodfileRewriteResult(val matchingCount: Int, val text: String)

private fun podDeclarations(text: String, executableLineStarts: Set<Int>): List<PodDeclaration> =
    POD_LINE.findAll(text)
        .filter { it.range.first in executableLineStarts }
        .map { match -> PodDeclaration(match, match.groupValues[3], match.groupValues[8]) }
        .filter { it.hasBalancedQuotes }
        .toList()

internal fun planPodfileRewrite(text: String, oldName: String, newName: String): PodfileRewriteResult {
    val executableLineStarts = rubyExecutableLineStarts(text)
    val declarations = podDeclarations(text, executableLineStarts)
    val matching = declarations.filter { it.name == oldName && it.pathTail == oldName }
    if (matching.isEmpty()) return PodfileRewriteResult(0, text)
    var updated = text
    matching.asReversed().forEach { declaration ->
        val match = declaration.match
        val replacement = match.groupValues[1] + match.groupValues[2] + newName + match.groupValues[4] +
            match.groupValues[5] + match.groupValues[6] + match.groupValues[7] + newName +
            match.groupValues[9] + match.groupValues[10]
        updated = updated.replaceRange(match.range, replacement)
    }
    return PodfileRewriteResult(matching.size, updated)
}

internal fun matchingPodDeclarationCount(text: String, name: String): Int =
    planPodfileRewrite(text, name, name).matchingCount

/**
 * Rewrite only executable, line-anchored local-pod declarations whose pod name
 * and path tail both equal [oldName]. Quotes, whitespace, path syntax, nested path,
 * comments, and line endings are preserved byte-for-byte around the two names.
 */
internal fun rewritePodfileContent(text: String, oldName: String, newName: String): String =
    planPodfileRewrite(text, oldName, newName).text

private data class RubyHeredoc(val delimiter: String, val allowsIndent: Boolean)

private data class RubyLiteral(
    val opening: Char?,
    val closing: Char,
    var depth: Int = 1,
    val regex: Boolean = false,
    var inRegexClass: Boolean = false,
)

/**
 * Returns starts of Ruby lines that begin in executable code. Pod declarations
 * are accepted only on those lines, so declaration-looking text in multiline
 * strings, percent literals, regex literals, heredocs, embedded documentation,
 * and the data section is never rewritten. Malformed open regions fail closed.
 */
private fun rubyExecutableLineStarts(text: String): Set<Int> {
    val executable = linkedSetOf<Int>()
    val pendingHeredocs = ArrayDeque<RubyHeredoc>()
    var heredoc: RubyHeredoc? = null
    var literal: RubyLiteral? = null
    var embeddedDocumentation = false
    var dataSection = false
    var lineStart = 0

    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val lineEnd = if (newline > lineStart && text[newline - 1] == '\r') newline - 1 else newline
        val line = text.substring(lineStart, lineEnd)

        when {
            dataSection -> Unit
            embeddedDocumentation -> {
                if (line.startsWith("=end") && line.drop(4).all(Char::isWhitespace)) {
                    embeddedDocumentation = false
                }
            }
            heredoc != null -> {
                val active = heredoc
                val candidate = if (active.allowsIndent) line.trimStart() else line
                if (candidate == active.delimiter) heredoc = pendingHeredocs.removeFirstOrNull()
            }
            literal == null && line.startsWith("=begin") && line.drop(6).let { it.isEmpty() || it.first().isWhitespace() } -> {
                embeddedDocumentation = true
            }
            literal == null && line == "__END__" -> dataSection = true
            else -> {
                if (literal == null) executable += lineStart
                literal = scanRubyLine(line, literal, pendingHeredocs)
                if (literal == null && pendingHeredocs.isNotEmpty()) {
                    heredoc = pendingHeredocs.removeFirst()
                }
            }
        }

        if (newline == text.length) break
        lineStart = newline + 1
    }

    require(!embeddedDocumentation) { "Podfile contains an unterminated =begin/=end documentation block" }
    require(literal == null) { "Podfile contains an unterminated quoted or delimited literal" }
    require(heredoc == null && pendingHeredocs.isEmpty()) { "Podfile contains an unterminated heredoc" }
    return executable
}

private fun scanRubyLine(
    line: String,
    initialLiteral: RubyLiteral?,
    pendingHeredocs: ArrayDeque<RubyHeredoc>,
): RubyLiteral? {
    var literal = initialLiteral
    var i = 0
    var lastNonWhitespaceIndex = -1
    var lastWordStart = -1
    var lastWordEnd = -1
    var activeWordStart = -1
    var previousRawCharacterWasWord = false
    val lineLastNonWhitespaceIndex = line.indexOfLast { !it.isWhitespace() }

    fun observeCodeCharacter(index: Int) {
        val character = line[index]
        val isWord = character.isLetterOrDigit() || character == '_'
        when {
            character.isWhitespace() -> previousRawCharacterWasWord = false
            isWord -> {
                if (!previousRawCharacterWasWord) activeWordStart = index
                lastWordStart = activeWordStart
                lastWordEnd = index + 1
                lastNonWhitespaceIndex = index
                previousRawCharacterWasWord = true
            }
            else -> {
                lastWordStart = -1
                lastWordEnd = -1
                activeWordStart = -1
                lastNonWhitespaceIndex = index
                previousRawCharacterWasWord = false
            }
        }
    }

    fun previousWordIsRegexPrefix(): Boolean {
        if (lastWordStart < 0 || lastWordEnd < 0 || lastWordEnd != lastNonWhitespaceIndex + 1) return false
        val length = lastWordEnd - lastWordStart
        return RUBY_REGEX_PREFIX_KEYWORDS.any { keyword ->
            keyword.length == length && line.regionMatches(lastWordStart, keyword, 0, length)
        }
    }

    while (i < line.length) {
        val active = literal
        if (active != null) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> i += 2
                active.regex && c == '[' && !active.inRegexClass -> {
                    active.inRegexClass = true
                    i++
                }
                active.regex && c == ']' && active.inRegexClass -> {
                    active.inRegexClass = false
                    i++
                }
                active.regex && active.inRegexClass -> i++
                active.opening != null && c == active.opening -> {
                    active.depth++
                    i++
                }
                c == active.closing -> {
                    active.depth--
                    if (active.depth == 0) observeCodeCharacter(i)
                    i++
                    if (active.depth == 0) literal = null
                }
                else -> i++
            }
            continue
        }

        when (val c = line[i]) {
            '#' -> break
            '\'', '"', '`' -> {
                observeCodeCharacter(i)
                literal = RubyLiteral(null, c)
                i++
            }
            '<' -> {
                val heredocMatch = RUBY_HEREDOC.find(line, i)
                    ?.takeIf { it.range.first == i }
                if (heredocMatch == null) {
                    observeCodeCharacter(i)
                    i++
                } else {
                    val delimiter = heredocMatch.groupValues[3].ifEmpty { heredocMatch.groupValues[4] }
                    pendingHeredocs += RubyHeredoc(
                        delimiter = delimiter,
                        allowsIndent = heredocMatch.groupValues[1].isNotEmpty(),
                    )
                    (i..heredocMatch.range.last).forEach(::observeCodeCharacter)
                    i = heredocMatch.range.last + 1
                }
            }
            '%' -> {
                val typeOffset = if (line.getOrNull(i + 1) in RUBY_PERCENT_TYPES) 1 else 0
                val delimiterIndex = i + 1 + typeOffset
                val opening = line.getOrNull(delimiterIndex)
                if (opening == null || opening.isLetterOrDigit() || opening.isWhitespace()) {
                    observeCodeCharacter(i)
                    i++
                } else {
                    val closing = RUBY_DELIMITER_PAIRS[opening] ?: opening
                    literal = RubyLiteral(opening.takeIf { it in RUBY_DELIMITER_PAIRS }, closing)
                    (i..delimiterIndex).forEach(::observeCodeCharacter)
                    i = delimiterIndex + 1
                }
            }
            '/' -> {
                val previous = line.getOrNull(lastNonWhitespaceIndex)
                if (
                    previous == null || previous in RUBY_REGEX_PREFIXES ||
                    previousWordIsRegexPrefix() || i == lineLastNonWhitespaceIndex
                ) {
                    literal = RubyLiteral(null, '/', regex = true)
                }
                observeCodeCharacter(i)
                i++
            }
            else -> {
                observeCodeCharacter(i)
                i++
            }
        }
    }
    return literal
}

private val RUBY_HEREDOC = Regex("""<<([-~]?)(?:(['\"`])([A-Za-z_][A-Za-z0-9_]*)\2|([A-Za-z_][A-Za-z0-9_]*))""")
private val RUBY_PERCENT_TYPES = setOf('q', 'Q', 'w', 'W', 'i', 'I', 'x', 'r', 's')
private val RUBY_DELIMITER_PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '<' to '>')
private val RUBY_REGEX_PREFIXES = "=([{!,:;?~&|+-*%^<>"
private val RUBY_REGEX_PREFIX_KEYWORDS = setOf(
    "and", "case", "do", "elsif", "if", "in", "not", "or", "return", "then",
    "unless", "until", "when", "while", "yield",
)
private val SWIFT_MODULE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun requireSwiftModuleIdentifier(value: String, label: String = "module name") {
    require(value.length <= 255 && SWIFT_MODULE_IDENTIFIER.matches(value)) {
        "$label '${diagnosticSafeText(value, 255)}' is not a valid bounded Swift module identifier"
    }
}

/**
 * Rewrite exact plain `import OldModule` statements that occur in Swift code.
 * A conservative lexer masks line comments, nested block comments, normal and
 * multiline strings, raw strings, and extended regex literals before imports
 * are located. Import-looking text in those regions is therefore never changed.
 */
internal fun rewriteSwiftImport(text: String, oldName: String, newName: String): String {
    requireSwiftModuleIdentifier(oldName, "old shared module name")
    requireSwiftModuleIdentifier(newName, "new shared module name")
    val codeMask = swiftCodeMask(text)
    val regex = Regex("(?m)^([ \\t]*import[ \\t]+)${Regex.escape(oldName)}([ \\t]*)$")
    val ranges = regex.findAll(codeMask).map { match ->
        val prefixLength = match.groupValues[1].length
        (match.range.first + prefixLength) until (match.range.first + prefixLength + oldName.length)
    }.toList()
    var updated = text
    ranges.asReversed().forEach { updated = updated.replaceRange(it, newName) }
    return updated
}

/** Same length/newlines as [text]; non-code characters are replaced with spaces. */
private fun swiftCodeMask(text: String): String {
    val mask = text.toCharArray()
    fun hide(index: Int) {
        if (mask[index] != '\n' && mask[index] != '\r') mask[index] = ' '
    }

    var i = 0
    var blockDepth = 0
    var stringHashes = -1
    var stringClosing = ""
    var stringEscapePrefix = ""
    var regexHashes = -1
    var regexClosing = ""

    fun beginString(hashes: Int, multiline: Boolean) {
        require(hashes <= MAX_SWIFT_EXTENDED_LITERAL_HASHES) {
            "Swift extended literal delimiters with more than $MAX_SWIFT_EXTENDED_LITERAL_HASHES hashes " +
                "are outside this migration parser's bounded grammar"
        }
        stringHashes = hashes
        stringClosing = "\"".repeat(if (multiline) 3 else 1) + "#".repeat(hashes)
        stringEscapePrefix = "\\" + "#".repeat(hashes)
    }

    fun beginRegex(hashes: Int) {
        require(hashes in 1..MAX_SWIFT_EXTENDED_LITERAL_HASHES) {
            "Swift extended regex delimiters must use 1..$MAX_SWIFT_EXTENDED_LITERAL_HASHES hashes"
        }
        regexHashes = hashes
        regexClosing = "/" + "#".repeat(hashes)
    }

    while (i < text.length) {
        if (blockDepth > 0) {
            when {
                text.startsWith("/*", i) -> {
                    hide(i); if (i + 1 < text.length) hide(i + 1)
                    blockDepth++; i += 2
                }
                text.startsWith("*/", i) -> {
                    hide(i); if (i + 1 < text.length) hide(i + 1)
                    blockDepth--; i += 2
                }
                else -> { hide(i); i++ }
            }
            continue
        }

        if (stringHashes >= 0) {
            if (text.startsWith(stringEscapePrefix, i) && i + stringEscapePrefix.length < text.length) {
                // A Swift escape consumes the next scalar. In particular, the
                // first quote in \""" / \#"""# is escaped and therefore cannot
                // begin the closing delimiter.
                val escapedLength = stringEscapePrefix.length + 1
                repeat(escapedLength) { hide(i + it) }
                i += escapedLength
            } else if (text.startsWith(stringClosing, i)) {
                repeat(stringClosing.length) { hide(i + it) }
                i += stringClosing.length
                stringHashes = -1
                stringClosing = ""
                stringEscapePrefix = ""
            } else {
                hide(i); i++
            }
            continue
        }

        if (regexHashes >= 0) {
            when {
                text[i] == '\\' -> {
                    // Regex escapes consume the following UTF-16 unit. This is
                    // enough to keep an escaped slash from being mistaken for
                    // the closing /#...# delimiter; the parser remains
                    // deliberately lexical rather than interpreting regexes.
                    hide(i)
                    i++
                    if (i < text.length) {
                        hide(i)
                        i++
                    }
                }
                text.startsWith(regexClosing, i) -> {
                    repeat(regexClosing.length) { hide(i + it) }
                    i += regexClosing.length
                    regexHashes = -1
                    regexClosing = ""
                }
                else -> {
                    hide(i)
                    i++
                }
            }
            continue
        }

        when {
            text.startsWith("//", i) -> {
                while (i < text.length && text[i] != '\n' && text[i] != '\r') { hide(i); i++ }
            }
            text.startsWith("/*", i) -> {
                hide(i); hide(i + 1); blockDepth = 1; i += 2
            }
            text[i] == '"' -> {
                val triple = text.startsWith("\"\"\"", i)
                val count = if (triple) 3 else 1
                beginString(hashes = 0, multiline = triple)
                repeat(count) { hide(i + it) }
                i += count
            }
            text[i] == '#' -> {
                var hashes = 0
                while (i + hashes < text.length && text[i + hashes] == '#') hashes++
                val quote = i + hashes
                if (quote < text.length && text[quote] == '"') {
                    val triple = text.startsWith("\"\"\"", quote)
                    val count = hashes + if (triple) 3 else 1
                    beginString(hashes = hashes, multiline = triple)
                    repeat(count) { hide(i + it) }
                    i += count
                } else if (quote < text.length && text[quote] == '/') {
                    val count = hashes + 1
                    beginRegex(hashes)
                    repeat(count) { hide(i + it) }
                    i += count
                } else i++
            }
            else -> i++
        }
    }
    require(blockDepth == 0) { "Swift source contains an unterminated block comment" }
    require(stringHashes < 0) { "Swift source contains an unterminated string literal" }
    require(regexHashes < 0) { "Swift source contains an unterminated extended regex literal" }
    return String(mask)
}

private const val MAX_SWIFT_EXTENDED_LITERAL_HASHES = 256
