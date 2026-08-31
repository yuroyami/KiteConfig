package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteropOptInsTest {

    @Test
    fun `defaults cover the common interop markers`() {
        assertTrue("kotlinx.cinterop.ExperimentalForeignApi" in DEFAULT_INTEROP_OPT_INS)
        assertTrue("kotlin.experimental.ExperimentalObjCName" in DEFAULT_INTEROP_OPT_INS)
        assertTrue("kotlin.experimental.ExperimentalNativeApi" in DEFAULT_INTEROP_OPT_INS)
    }

    @Test
    fun `no extras returns the defaults verbatim`() {
        assertEquals(DEFAULT_INTEROP_OPT_INS, interopOptIns(emptyList()))
    }

    @Test
    fun `extras are appended after the defaults`() {
        val result = interopOptIns(listOf("com.example.MyMarker"))
        assertEquals(DEFAULT_INTEROP_OPT_INS + "com.example.MyMarker", result)
    }

    @Test
    fun `duplicate extras are de-duplicated, defaults kept first`() {
        val result = interopOptIns(
            listOf("kotlinx.cinterop.ExperimentalForeignApi", "com.example.MyMarker", "com.example.MyMarker"),
        )
        assertEquals(DEFAULT_INTEROP_OPT_INS + "com.example.MyMarker", result)
    }

    @Test
    fun `includeBuiltIns=false drops the defaults, keeping only extras`() {
        val result = interopOptIns(listOf("com.example.MyMarker"), includeBuiltIns = false)
        assertEquals(listOf("com.example.MyMarker"), result)
        assertTrue(DEFAULT_INTEROP_OPT_INS.none { it in result })
    }

    @Test
    fun `includeBuiltIns=false with no extras opts in to nothing`() {
        assertEquals(emptyList<String>(), interopOptIns(emptyList(), includeBuiltIns = false))
    }
}
