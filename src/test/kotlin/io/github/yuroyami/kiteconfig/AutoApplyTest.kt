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

    @Test
    fun `IDE import respects shared and iOS automatic application gates`() {
        fun case(expected: Boolean, configure: KiteConfigExtension.() -> Unit) = configure to expected
        val cases = listOf(
            case(false) {},
            case(true) { autoApply.set(true) },
            case(false) { autoApply.set(true); ios.autoApply.set(false) },
            case(true) { autoApply.set(false); ios.autoApply.set(true) },
            case(false) { autoApply.set(true); skip(ios) },
            case(false) { autoApply.set(true); only(android) },
        )
        cases.forEachIndexed { index, (configure, expected) ->
            val root = ProjectBuilder.builder().build()
            val child = ProjectBuilder.builder().withName("shared").withParent(root).build()
            root.plugins.apply(KiteConfigPlugin::class.java)
            root.extensions.getByType(KiteConfigExtension::class.java).configure()
            // KGP registers this task after the root plugin has been applied.
            val task = child.tasks.register("prepareKotlinIdeaImport").get()
            val dependencies = task.taskDependencies.getDependencies(task).map { it.path }
            org.junit.jupiter.api.Assertions.assertEquals(
                expected, ":kiteApplyIos" in dependencies, "case $index: $dependencies",
            )
        }
    }

    @Test
    fun `ordinary Android builds remain scoped while iOS builds still apply automatically`() {
        val root = ProjectBuilder.builder().build()
        val child = ProjectBuilder.builder().withName("shared").withParent(root).build()
        root.plugins.apply(KiteConfigPlugin::class.java)
        root.extensions.getByType(KiteConfigExtension::class.java).autoApply.set(true)
        val android = child.tasks.register("preBuild").get()
        assertFalse(android.taskDependencies.getDependencies(android).any { it.path == ":kiteApplyIos" })
        listOf("embedAndSignAppleFrameworkForXcode", "linkReleaseFrameworkIosArm64").forEach { name ->
            val ios = child.tasks.register(name).get()
            assertTrue(ios.taskDependencies.getDependencies(ios).any { it.path == ":kiteApplyIos" })
        }
    }

}
