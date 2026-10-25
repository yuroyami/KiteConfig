package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildConfigGenTest {

    @Test
    fun `the generated class defaults to KiteBuildConfig in the kiteconfig package`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        val ext = project.extensions.getByType(KiteConfigExtension::class.java)

        assertEquals("KiteBuildConfig", ext.buildConfig.className.get())
        assertEquals("kiteconfig.generated", ext.buildConfig.packageName.get())
    }

    /** Everything Gradle would print for [failure], including wrapped causes. */
    private fun reportedText(failure: Throwable): String = generateSequence(failure, Throwable::cause)
        .mapNotNull(Throwable::message)
        .joinToString("\n")

    @Test
    fun `a field whose provider has no value names that field instead of voiding the list`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.stringField("BASE_URL", "https://example.invalid")
        // -PpublicChannel was never passed, so this provider has no value. Gradle's
        // ListProperty.add(Provider) makes the WHOLE list absent in that case, which
        // used to surface far away as "customFields doesn't have a configured value".
        extension.stringField("CHANNEL", project.providers.gradleProperty("publicChannel"))

        // Gradle wraps a throwing provider in PropertyQueryException, so assert on the
        // whole chain: that is the text a consumer actually reads in the build output.
        val reported = reportedText(assertThrows(Exception::class.java) { extension.fields.get() })
        assertTrue(reported.contains("CHANNEL"), reported)
        assertTrue(reported.contains("orElse"), reported)
        assertTrue(reported.contains("buildConfig"), reported)
    }

    @Test
    fun `a field whose provider has a value is emitted normally`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.stringField("CHANNEL", project.providers.provider { "stable" })

        assertEquals(listOf("CHANNEL: String = \"stable\""), extension.fields.get())
    }

    /** A lazy value should not be a privilege of the string helper. */
    @Test
    fun `every field type accepts a provider`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.intField("TIMEOUT_MS", project.providers.provider { 30_000 })
        extension.longField("BUDGET", project.providers.provider { 9_000_000_000L })
        extension.booleanField("ANALYTICS", project.providers.provider { true })
        extension.doubleField("RATIO", project.providers.provider { 0.75 })

        assertEquals(
            listOf(
                "TIMEOUT_MS: Int = 30000",
                "BUDGET: Long = 9000000000L",
                "ANALYTICS: Boolean = true",
                "RATIO: Double = 0.75",
            ),
            extension.fields.get(),
        )
    }

    @Test
    fun `a non-string field with an empty provider names its own helper`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.objects.newInstance(KiteConfigBuildConfigExtension::class.java)
        extension.fields.convention(emptyList())
        extension.intField("TIMEOUT_MS", project.providers.gradleProperty("t").map(String::toInt))

        val reported = reportedText(assertThrows(Exception::class.java) { extension.fields.get() })
        assertTrue(reported.contains("intField"), reported)
        assertTrue(reported.contains("TIMEOUT_MS"), reported)
        assertTrue(reported.contains("orElse"), reported)
    }
}
