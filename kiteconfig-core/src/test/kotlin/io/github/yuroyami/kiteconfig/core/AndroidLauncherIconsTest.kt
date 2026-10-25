package io.github.yuroyami.kiteconfig.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidLauncherIconsTest {

    @Test
    fun `the android density table runs from mdpi to xxxhdpi`() {
        val expected = listOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
        assertEquals(expected, ANDROID_DENSITIES, ANDROID_DENSITIES.toString())
    }
}
