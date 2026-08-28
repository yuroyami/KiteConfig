package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KiteFlowTest {

    // Gradle's class generator refuses a private type, so this one is internal.
    internal abstract class TestScope : KiteFlowScope()
    private class Ref(override val platform: KitePlatform) : KitePlatformRef

    private fun scope(): KiteFlowScope =
        ProjectBuilder.builder().build().objects.newInstance(TestScope::class.java)

    @Test
    fun `default is flow everywhere`() {
        val s = scope()
        KitePlatform.entries.forEach { assertTrue(s.flowsTo(it).get()) }
    }

    @Test
    fun `skip stops one platform only`() {
        val s = scope()
        s.skip(Ref(KitePlatform.IOS))
        assertFalse(s.flowsTo(KitePlatform.IOS).get())
        assertTrue(s.flowsTo(KitePlatform.ANDROID).get())
        assertTrue(s.flowsTo(KitePlatform.DESKTOP).get())
    }

    @Test
    fun `only allows listed platforms only`() {
        val s = scope()
        s.only(Ref(KitePlatform.ANDROID), Ref(KitePlatform.DESKTOP))
        assertTrue(s.flowsTo(KitePlatform.ANDROID).get())
        assertFalse(s.flowsTo(KitePlatform.IOS).get())
        assertTrue(s.flowsTo(KitePlatform.DESKTOP).get())
    }

    @Test
    fun `skip beats only when both name the same platform`() {
        val s = scope()
        s.only(Ref(KitePlatform.ANDROID))
        s.skip(Ref(KitePlatform.ANDROID))
        assertFalse(s.flowsTo(KitePlatform.ANDROID).get())
    }
}
