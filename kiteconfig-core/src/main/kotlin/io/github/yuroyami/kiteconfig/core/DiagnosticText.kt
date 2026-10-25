package io.github.yuroyami.kiteconfig.core

/** Bound and escape uncontrolled source/provider text before console or report use. */
@InternalKiteCoreApi
public fun diagnosticSafeText(value: String, maximumChars: Int = 2_048): String {
    val abbreviated = if (value.length > maximumChars) value.take(maximumChars) + "…" else value
    return buildString(abbreviated.length) {
        abbreviated.forEach { character ->
            when (character) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (
                    character.isISOControl() || character.code in 0xD800..0xDFFF ||
                    character == '\u2028' || character == '\u2029'
                ) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
