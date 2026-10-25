package io.github.yuroyami.kiteconfig.core

/** A [KiteLog] that keeps every message, so a test can read what core code logged. */
internal class RecordingLog : KiteLog {
    val lines: MutableList<String> = mutableListOf()

    override fun lifecycle(message: String) {
        lines += message
    }

    override fun info(message: String) {
        lines += message
    }
}
