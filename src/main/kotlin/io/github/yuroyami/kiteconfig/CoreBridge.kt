package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.KiteLog
import io.github.yuroyami.kiteconfig.core.PlistConflictPolicy as CorePlistConflictPolicy
import org.gradle.api.logging.Logger

/** Lets core code write to this Gradle logger. */
internal fun Logger.asKiteLog(): KiteLog {
    val logger = this
    return object : KiteLog {
        override fun lifecycle(message: String) = logger.lifecycle(message)
        override fun info(message: String) = logger.info(message)
    }
}

/** The core policy with the same name as this public policy. */
internal fun PlistConflictPolicy.toCore(): CorePlistConflictPolicy = when (this) {
    PlistConflictPolicy.FAIL -> CorePlistConflictPolicy.FAIL
    PlistConflictPolicy.KEEP -> CorePlistConflictPolicy.KEEP
    PlistConflictPolicy.REPLACE -> CorePlistConflictPolicy.REPLACE
}
