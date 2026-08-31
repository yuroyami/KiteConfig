package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    /**
     * A task group is user-visible in `./gradlew tasks`. Two rewrite tasks kept a
     * group name left over from the rename and no test noticed, because the suite
     * only checked that the tasks existed.
     */
    @Test
    fun `every kite task is in the kiteconfig group`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)

        val misfiled = project.tasks
            .filter { it.name.startsWith("kite") }
            .filterNot { it.group == "kiteconfig" }
            .map { "${it.name} is in group '${it.group}'" }

        assertEquals(emptyList<String>(), misfiled, misfiled.joinToString("\n"))
        assertTrue(project.tasks.count { it.name.startsWith("kite") } >= 6)
    }

}
