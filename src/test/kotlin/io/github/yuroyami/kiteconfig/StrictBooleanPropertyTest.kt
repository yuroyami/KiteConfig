package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `-Pkiteconfig.dryRun=treu` must never be read as "no preview, mutate for real".
 * Kotlin's String.toBoolean() maps every typo to false, so these flags need a
 * parser that refuses anything it does not recognise.
 */
class StrictBooleanPropertyTest {

    @Test
    fun `exactly true and false parse, in any case`() {
        listOf("true", "TRUE", "True").forEach {
            assertEquals(true, strictBooleanProperty("kiteconfig.dryRun", it), it)
        }
        listOf("false", "FALSE", "False").forEach {
            assertEquals(false, strictBooleanProperty("kiteconfig.dryRun", it), it)
        }
    }

    @Test
    fun `surrounding whitespace is tolerated, not treated as a typo`() {
        assertEquals(true, strictBooleanProperty("kiteconfig.dryRun", "  true "))
    }

    @Test
    fun `a misspelling fails loudly instead of silently disabling the preview`() {
        val failure = assertThrows(GradleException::class.java) {
            strictBooleanProperty("kiteconfig.dryRun", "treu")
        }
        assertTrue(failure.message.orEmpty().contains("kiteconfig.dryRun"), failure.message)
        assertTrue(failure.message.orEmpty().contains("treu"), failure.message)
    }

    @Test
    fun `empty, blank, numeric, and yes-like values are all rejected`() {
        listOf("", "   ", "1", "0", "yes", "no", "on", "off", "y", "n").forEach { value ->
            assertThrows(GradleException::class.java, {
                strictBooleanProperty("kiteconfig.backups", value)
            }, "expected \"$value\" to be rejected")
        }
    }

    @Test
    fun `the message names the offending property so the operator can find it`() {
        val failure = assertThrows(GradleException::class.java) {
            strictBooleanProperty("kiteconfig.backups", "0")
        }
        assertTrue(failure.message.orEmpty().contains("kiteconfig.backups"), failure.message)
    }
}
