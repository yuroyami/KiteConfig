package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One switch decides who applies the source changes.
 *
 * There used to be a rewrite block per topic, an auto flag inside each one, and
 * a task name to remember. The plan is now derived from what you declared;
 * autoApply says whether an ordinary build runs it, and kiteApply runs it when
 * you ask.
 */
class AutoApplyTest {

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
    fun `nothing rides an ordinary build by default`() {
        val e = ext().withArt()

        assertFalse(e.effectiveAutoApplyFor(KitePlatform.ANDROID).get())
        assertFalse(e.effectiveAutoRewriteLogo.get())
        assertFalse(e.effectiveAutoRewriteXcode.get())
    }

    @Test
    fun `the shared switch reaches every platform`() {
        val e = ext().withArt()
        e.autoApply.set(true)

        assertTrue(e.effectiveAutoApplyFor(KitePlatform.ANDROID).get())
        assertTrue(e.effectiveAutoApplyFor(KitePlatform.IOS).get())
        assertTrue(e.effectiveAutoRewriteLogo.get())
        assertTrue(e.effectiveAutoRewriteXcode.get())
    }

    @Test
    fun `one platform can stay manual`() {
        val e = ext().withArt()
        e.autoApply.set(true)
        e.ios.autoApply.set(false)

        assertTrue(e.effectiveAutoRewriteLogo.get())
        assertFalse(e.effectiveAutoRewriteXcode.get())
    }

    /** Automatic application schedules the plan; it does not invent work. */
    @Test
    fun `a platform with nothing to install schedules nothing`() {
        val e = ext()
        e.autoApply.set(true)

        assertFalse(e.effectiveAutoRewriteLogo.get())
    }

    @Test
    fun `an excluded platform is never applied to`() {
        val e = ext().withArt()
        e.autoApply.set(true)
        e.skip(e.ios)

        assertFalse(e.effectiveAutoRewriteXcode.get())
        assertTrue(e.effectiveAutoRewriteLogo.get())
    }

    /** Installing an icon claims its paths, with a recovery copy, and no extra flag. */
    @Test
    fun `installing a logo adopts the paths it occupies`() {
        assertTrue(ext().withArt().effectiveTakeOverLegacyIcons.get())
        assertFalse(ext().effectiveTakeOverLegacyIcons.get())
    }
}
