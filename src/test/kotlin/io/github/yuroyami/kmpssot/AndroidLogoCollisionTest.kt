package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidLogoCollisionTest {

    // --- T06: template launcher icons that share a generated PNG's stem but a
    // different extension are the AAPT2-merge hazard the plugin must flag/remove. -
    @Test
    fun `flags template webp icons that collide with generated PNGs`(@TempDir res: File) {
        File(res, "mipmap-hdpi").mkdirs()
        File(res, "mipmap-xxhdpi").mkdirs()
        File(res, "mipmap-hdpi/ic_launcher.webp").writeText("x")         // collides
        File(res, "mipmap-xxhdpi/ic_launcher_round.webp").writeText("x") // collides
        File(res, "mipmap-hdpi/ic_launcher.png").writeText("x")          // generated → not a collision
        File(res, "mipmap-hdpi/splash.webp").writeText("x")              // not a launcher stem

        val hits = SyncAndroidLogoTask.collidingTemplateIcons(res).map { it.name }.sorted()
        assertEquals(listOf("ic_launcher.webp", "ic_launcher_round.webp"), hits)
    }

    @Test
    fun `a clean res dir has no collisions`(@TempDir res: File) {
        File(res, "mipmap-hdpi").mkdirs()
        File(res, "mipmap-hdpi/ic_launcher.png").writeText("x")
        assertTrue(SyncAndroidLogoTask.collidingTemplateIcons(res).isEmpty())
    }
}
