package io.github.yuroyami.kiteconfig

import java.nio.file.Path

private const val ESC = "["
private const val RESET = "[0m"

/**
 * What a piece of console text *means*, never what colour it is.
 *
 * Call sites pick a role and the theme decides how to draw it, so a terminal
 * that cannot show colour degrades to the same words rather than to escape
 * codes leaking into a log file.
 */
internal enum class KiteConfigStyle(internal val code: String) {
    /** A report title, for example "Resolved single source of truth". */
    HEADING("1;36"),

    /** A section name inside a report, for example "Android". */
    SECTION("1;34"),

    /** A property name in a key/value row. */
    KEY("36"),

    /** A finding that passed. */
    PASS("32"),

    /** A finding worth reading but not fatal. */
    WARN("33"),

    /** A finding that fails the build, or would. */
    FAIL("1;31"),

    /** A check that did not apply to this build, so it recedes furthest. */
    SKIP("2;90"),

    /** Supporting text: notes, hints, unset markers. */
    MUTED("90"),

    /** A filesystem path. */
    PATH("35"),
}

/** How Gradle was asked to render the build log. */
internal enum class ConsoleMode { PLAIN, RICH, AUTO }

/**
 * Paints console text, or does not, depending on where the output is going.
 *
 * Build [colored] once with [resolveColorSupport] and pass it down as a task
 * input; never re-detect inside a task action.
 */
internal class KiteConfigConsole(private val colored: Boolean) {

    /**
     * Wrap [text] in [style], closing the sequence on the same line.
     *
     * Paint whole lines rather than fragments. A reset in the middle of a line
     * breaks a plain-text `contains` in a consumer's own assertions, and breaks
     * grep for anyone reading CI output.
     */
    fun paint(text: String, style: KiteConfigStyle): String =
        if (!colored || text.isEmpty()) text else "$ESC${style.code}m$text$RESET"

    /** Right-pad [key] to [width], then join it to [value] with the shared separator. */
    fun row(key: String, value: String, width: Int, indent: String): String =
        indent + paint(key.padEnd(width), KiteConfigStyle.KEY) + " = " + value
}

/**
 * Lay [rows] out so every separator lands in one column.
 *
 * The column comes from the longest key present, so adding a property never
 * leaves the block looking ragged and nobody hand-counts spaces.
 */
internal fun alignedRows(
    rows: List<Pair<String, String>>,
    indent: String,
    console: KiteConfigConsole = KiteConfigConsole(colored = false),
): List<String> {
    if (rows.isEmpty()) return emptyList()
    val width = rows.maxOf { it.first.length }
    return rows.map { (key, value) -> console.row(key, value, width, indent) }
}

/**
 * Show [target] relative to [root] when it lives inside the project.
 *
 * An absolute path is correct but unreadable: it pushes the interesting part of
 * the line off the right edge of a terminal. Anything outside the project stays
 * absolute, because there the full location is the point.
 */
internal fun relativeDisplayPath(root: Path, target: Path): String = runCatching {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedTarget = target.toAbsolutePath().normalize()
    if (normalizedTarget.startsWith(normalizedRoot)) {
        normalizedRoot.relativize(normalizedTarget).toString().ifEmpty { "." }
    } else {
        normalizedTarget.toString()
    }
}.getOrElse { target.toString() }

/**
 * Decide whether this invocation may emit colour.
 *
 * Order matters. An explicit choice always wins, then the conventions a user
 * expects to be honoured, then what Gradle says about the console. The default
 * is off, because a stray escape code in a CI log is worse than a plain report.
 *
 * @param explicit `-Pkiteconfig.color`, or null when unset.
 * @param noColorEnv the `NO_COLOR` variable; any value, including empty, disables colour.
 * @param term the `TERM` variable; `dumb` disables colour.
 * @param terminalAttached whether a real console is attached, which is the only
 *   thing that can tell [ConsoleMode.AUTO] apart from a pipe.
 */
internal fun resolveColorSupport(
    explicit: Boolean?,
    noColorEnv: String?,
    term: String?,
    console: ConsoleMode,
    terminalAttached: Boolean,
): Boolean {
    explicit?.let { return it }
    if (noColorEnv != null) return false
    if (term.equals("dumb", ignoreCase = true)) return false
    return when (console) {
        ConsoleMode.PLAIN -> false
        ConsoleMode.RICH -> true
        ConsoleMode.AUTO -> terminalAttached
    }
}
