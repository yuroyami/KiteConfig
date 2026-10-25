package io.github.yuroyami.kiteconfig.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KiteConfigExceptionTest {

    @Test
    fun `a kite config exception keeps its message and its cause`() {
        val cause = IllegalStateException("inner")
        val failure = KiteConfigException("outer", cause)
        assertEquals("outer", failure.message, failure.toString())
        assertSame(cause, failure.cause, failure.toString())
    }
}
