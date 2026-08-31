package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TaskNamesTest {

    @Test
    fun `new entry points exist and old names are gone`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        listOf("kiteCheck", "kiteDoctor", "kiteVerify", "kitePlan", "kiteRewriteLogo", "kiteRewriteXcode")
            .forEach { assertNotNull(project.tasks.findByName(it), "missing $it") }
        listOf("kiteConfigCheck", "kiteConfigDoctor", "kiteConfigPlan", "kiteConfigSyncIosConfig")
            .forEach { assertNull(project.tasks.findByName(it), "$it should be gone") }
    }
}
