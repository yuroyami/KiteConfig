package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LogoScopeTest {

    private fun ext(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    private fun KiteConfigExtension.withArt() = apply {
        logo {
            foreground.set(File("art/logo.png"))
            background.set(color("#101014"))
        }
    }

    @Test
    fun `art alone is what makes the installers have work to do`() {
        assertFalse(ext().effectiveLogoRewriteArmed.get())
        assertTrue(ext().withArt().effectiveLogoRewriteArmed.get())
    }

    /** The topic turns itself on once there is art to install. */
    @Test
    fun `the topic is off without art and on with it`() {
        assertFalse(ext().logoFlowsTo(KitePlatform.ANDROID).get())
        assertTrue(ext().withArt().logoFlowsTo(KitePlatform.ANDROID).get())
    }

    @Test
    fun `desktop icons follow the topic and stop when desktop turns it off`() {
        assertTrue(ext().withArt().effectiveDesktopIcons.get())

        val off = ext().withArt()
        off.desktop.logo { enabled.set(false) }
        assertFalse(off.effectiveDesktopIcons.get())

        val skipped = ext().withArt()
        skipped.skip(skipped.desktop)
        assertFalse(skipped.effectiveDesktopIcons.get())
    }

    /**
     * One background, not two properties that must never both be set. Replacing
     * a color with an image has to leave no color behind.
     */
    @Test
    fun `the background is one choice`() {
        val colored = ext()
        colored.logo { background.set(color("#102A43")) }
        assertEquals("#102A43", colored.effectiveLogoBackgroundColorFor(KitePlatform.ANDROID).orNull)
        assertNull(colored.effectiveLogoBackgroundImageFor(KitePlatform.ANDROID).orNull)

        val pictured = ext()
        pictured.logo { background.set(image(File("art/bg.png"))) }
        assertNull(pictured.effectiveLogoBackgroundColorFor(KitePlatform.ANDROID).orNull)
        assertEquals(File("art/bg.png"), pictured.effectiveLogoBackgroundImageFor(KitePlatform.ANDROID).orNull)
    }

    @Test
    fun `a platform background replaces the shared one outright`() {
        val e = ext()
        e.logo { background.set(color("#102A43")) }
        e.ios.logo { background.set(image(File("art/ios-bg.png"))) }

        assertNull(e.effectiveLogoBackgroundColorFor(KitePlatform.IOS).orNull)
        assertEquals("#102A43", e.effectiveLogoBackgroundColorFor(KitePlatform.ANDROID).orNull)
    }

    @Test
    fun `the foreground scale is shared and overridable per platform`() {
        val e = ext().withArt()
        e.logo { foregroundScale.set(0.75) }
        e.android.logo { foregroundScale.set(0.61) }

        assertEquals(0.61, e.effectiveLogoScaleFor(KitePlatform.ANDROID).get())
        assertEquals(0.75, e.effectiveLogoScaleFor(KitePlatform.IOS).get())
        assertEquals(0.75, e.effectiveLogoScaleFor(KitePlatform.DESKTOP).get())
    }

    /** Unset, every renderer keeps the ratio it already used. */
    @Test
    fun `Android falls back to its own safe zone when no scale is set`() {
        assertEquals(66.0 / 108.0, ext().withArt().effectiveLogoSafeZone.get())

        val scaled = ext().withArt()
        scaled.logo { foregroundScale.set(0.5) }
        assertEquals(0.5, scaled.effectiveLogoSafeZone.get())
    }
}
