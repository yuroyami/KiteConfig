package io.github.yuroyami.kiteconfig

import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CoreBridgeTest {

    @Test
    fun `a gradle logger receives lifecycle and info lines from core`() {
        val recorded = mutableListOf<String>()
        val logger = Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
            if (method.name == "lifecycle" || method.name == "info") recorded += "${method.name}: ${args?.firstOrNull()}"
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        } as Logger

        logger.asKiteLog().lifecycle("a")
        logger.asKiteLog().info("b")

        assertEquals(listOf("lifecycle: a", "info: b"), recorded, recorded.toString())
    }
}
