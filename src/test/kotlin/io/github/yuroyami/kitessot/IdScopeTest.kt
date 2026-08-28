package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteSsotPlugin::class.java)
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `suffix corners build platform ids`() {
        val e = ext()
        e.id("com.example.jetzy") {
            android { suffix.set(".android") }
            ios { suffix.set(".ios") }
        }
        assertEquals("com.example.jetzy.android", e.androidApplicationId.get())
        assertEquals("com.example.jetzy.ios", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.desktopBundleId.get())
    }

    @Test
    fun `simple form works without corners`() {
        val e = ext()
        e.id.set("com.example.jetzy")
        assertEquals("com.example.jetzy", e.androidApplicationId.get())
    }
}
