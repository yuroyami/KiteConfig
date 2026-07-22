package io.github.yuroyami.kmpssot

/** Dot-separated Kotlin identifiers. */
private val PACKAGE_NAME_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*""")
private const val MAX_KOTLIN_PACKAGE_CHARS = 512

/** Kotlin hard keywords — invalid as a package segment (the generated file wouldn't compile). */
internal val KOTLIN_HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)

/**
 * Why [pkg] is unusable as the generated worker's Kotlin package, or null if it is
 * fine. Rejects non-identifier shapes and hard-keyword segments — either would make
 * the generated `KmpSsotIoWorker.kt` fail to compile with a confusing error far from
 * its cause. Returned as a sentence fragment the caller prefixes with the DSL path.
 */
internal fun invalidWorkerPackageReason(pkg: String): String? {
    if (pkg.length > MAX_KOTLIN_PACKAGE_CHARS) {
        return "exceeds the $MAX_KOTLIN_PACKAGE_CHARS-character generated-package limit."
    }
    if (!PACKAGE_NAME_RE.matches(pkg)) {
        return "is not a valid Kotlin package name. Use dot-separated identifiers, e.g. \"com.acme.app.generated\"."
    }
    val keyword = pkg.split('.').firstOrNull { it in KOTLIN_HARD_KEYWORDS }
    if (keyword != null) {
        return "segment \"$keyword\" is a Kotlin hard keyword — the generated file would not compile. " +
            "Rename that segment (e.g. \"${keyword}_\")."
    }
    if (pkg.split('.').any { it == "_" }) {
        return "contains the reserved underscore-only segment \"_\" — the generated file would not compile."
    }
    return null
}
