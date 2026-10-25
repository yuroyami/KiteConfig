package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.KiteLog
import org.gradle.api.logging.Logger

/** Lets core code write to this Gradle logger. */
internal fun Logger.asKiteLog(): KiteLog {
    val logger = this
    return object : KiteLog {
        override fun lifecycle(message: String) = logger.lifecycle(message)
        override fun info(message: String) = logger.info(message)
    }
}
