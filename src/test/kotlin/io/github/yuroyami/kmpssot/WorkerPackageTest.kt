package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerPackageTest {

    @Test
    fun `accepts valid packages`() {
        assertNull(invalidWorkerPackageReason("com.acme.app.generated"))
        assertNull(invalidWorkerPackageReason("kmpssot.generated"))
        assertNull(invalidWorkerPackageReason("a"))
    }

    @Test
    fun `rejects malformed packages`() {
        assertNotNull(invalidWorkerPackageReason("1bad.pkg")) // leading digit
        assertNotNull(invalidWorkerPackageReason("com..empty")) // empty segment
    }

    @Test
    fun `rejects a hard-keyword segment`() {
        val reason = invalidWorkerPackageReason("com.fun.gen")
        assertNotNull(reason)
        assertTrue(reason!!.contains("keyword"), reason)
    }
}
