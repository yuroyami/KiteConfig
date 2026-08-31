package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ConsoleStyleTest {

    private val plain = KiteConfigConsole(colored = false)
    private val rich = KiteConfigConsole(colored = true)

    @Test
    fun `a plain console emits the text untouched`() {
        assertEquals("[FAIL] KMPS021", plain.paint("[FAIL] KMPS021", KiteConfigStyle.FAIL))
        assertFalse(plain.paint("x", KiteConfigStyle.HEADING).contains(''))
    }

    @Test
    fun `a rich console wraps the text and always closes the sequence`() {
        val painted = rich.paint("hello", KiteConfigStyle.FAIL)
        assertTrue(painted.startsWith("["), painted)
        assertTrue(painted.endsWith("[0m"), painted)
        assertTrue(painted.contains("hello"), painted)
    }

    @Test
    fun `color never splits the text, so a grep for a finding still matches`() {
        // The whole line is painted as one span. Colouring only the [FAIL] tag would
        // insert a reset before the id and break `contains("[FAIL] KMPS021")`.
        val line = rich.paint("  [FAIL] KMPS021 iOS pbxproj: broken", KiteConfigStyle.FAIL)
        assertTrue(line.contains("[FAIL] KMPS021"), line)
    }

    @Test
    fun `styles are distinguishable from one another`() {
        val seen = KiteConfigStyle.entries.map { rich.paint("x", it) }.toSet()
        assertEquals(KiteConfigStyle.entries.size, seen.size, seen.toString())
    }

    @Test
    fun `rows share one computed column, however long the longest key is`() {
        val rows = alignedRows(
            listOf("appName" to "Jetzy", "androidApplicationId" to "com.example", "ndk" to "[unset]"),
            indent = "    ",
        )
        val columns = rows.map { it.indexOf(" = ") }.toSet()
        assertEquals(1, columns.size, rows.toString())
        assertTrue(rows.all { it.startsWith("    ") }, rows.toString())
        assertTrue(rows[0].contains("appName") && rows[0].endsWith("Jetzy"), rows[0])
    }

    @Test
    fun `an empty row set renders nothing rather than crashing`() {
        assertEquals(emptyList<String>(), alignedRows(emptyList(), indent = "  "))
    }

    @Test
    fun `a path inside the project shows relative, anything else stays absolute`() {
        val root = Path.of("/repo/app").toAbsolutePath()
        assertEquals(
            "iosApp/Podfile",
            relativeDisplayPath(root, root.resolve("iosApp/Podfile")),
        )
        val outside = Path.of("/elsewhere/thing.txt").toAbsolutePath()
        assertEquals(outside.toString(), relativeDisplayPath(root, outside))
    }

    @Test
    fun `an explicit color flag beats every environment signal`() {
        assertTrue(
            resolveColorSupport(
                explicit = true, noColorEnv = "1", term = "dumb",
                console = ConsoleMode.PLAIN, terminalAttached = false,
            ),
        )
        assertFalse(
            resolveColorSupport(
                explicit = false, noColorEnv = null, term = "xterm-256color",
                console = ConsoleMode.RICH, terminalAttached = true,
            ),
        )
    }

    @Test
    fun `NO_COLOR and a dumb terminal both disable color`() {
        assertFalse(
            resolveColorSupport(
                explicit = null, noColorEnv = "", term = "xterm",
                console = ConsoleMode.RICH, terminalAttached = true,
            ),
        )
        assertFalse(
            resolveColorSupport(
                explicit = null, noColorEnv = null, term = "dumb",
                console = ConsoleMode.RICH, terminalAttached = true,
            ),
        )
    }

    @Test
    fun `plain console mode disables color and rich enables it`() {
        assertFalse(
            resolveColorSupport(null, null, "xterm", ConsoleMode.PLAIN, terminalAttached = true),
        )
        assertTrue(
            resolveColorSupport(null, null, "xterm", ConsoleMode.RICH, terminalAttached = false),
        )
    }

    @Test
    fun `auto mode colors only when a real terminal is attached`() {
        assertTrue(
            resolveColorSupport(null, null, "xterm", ConsoleMode.AUTO, terminalAttached = true),
        )
        // Piped output and CI logs land here, so nothing may inject escape codes.
        assertFalse(
            resolveColorSupport(null, null, "xterm", ConsoleMode.AUTO, terminalAttached = false),
        )
    }
}
