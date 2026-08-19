package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `-Pkitessot.dryRun=treu` must never be read as "no preview, mutate for real".
 * Kotlin's String.toBoolean() maps every typo to false, so these flags need a
 * parser that refuses anything it does not recognise.
 */
class StrictBooleanPropertyTest {

    @Test
    fun `exactly true and false parse, in any case`() {
        listOf("true", "TRUE", "True").forEach {
            assertEquals(true, strictBooleanProperty("kitessot.dryRun", it), it)
        }
        listOf("false", "FALSE", "False").forEach {
            assertEquals(false, strictBooleanProperty("kitessot.dryRun", it), it)
        }
    }

    @Test
    fun `surrounding whitespace is tolerated, not treated as a typo`() {
        assertEquals(true, strictBooleanProperty("kitessot.dryRun", "  true "))
    }

    @Test
    fun `a misspelling fails loudly instead of silently disabling the preview`() {
        val failure = assertThrows(GradleException::class.java) {
            strictBooleanProperty("kitessot.dryRun", "treu")
        }
        assertTrue(failure.message.orEmpty().contains("kitessot.dryRun"), failure.message)
        assertTrue(failure.message.orEmpty().contains("treu"), failure.message)
    }

    @Test
    fun `empty, blank, numeric, and yes-like values are all rejected`() {
        listOf("", "   ", "1", "0", "yes", "no", "on", "off", "y", "n").forEach { value ->
            assertThrows(GradleException::class.java, {
                strictBooleanProperty("kitessot.backups", value)
            }, "expected \"$value\" to be rejected")
        }
    }

    @Test
    fun `the message names the offending property so the operator can find it`() {
        val failure = assertThrows(GradleException::class.java) {
            strictBooleanProperty("kitessot.backups", "0")
        }
        assertTrue(failure.message.orEmpty().contains("kitessot.backups"), failure.message)
    }
}
