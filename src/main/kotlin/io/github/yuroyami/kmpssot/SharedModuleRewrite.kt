package io.github.yuroyami.kmpssot

/**
 * Pure helpers for the shared-module rename SSOT. Extracted from the task so the
 * tricky import-boundary cases can be unit tested.
 */

// Matches both Podfile path syntaxes:
//   pod 'X', :path => '../X'            (classic hash-rocket)
//   pod 'X', path: '../X'              (modern Ruby 1.9 hash)
//   pod 'X', :path => '../sub/dir/X'   (nested path)
// Captures: (1) pod name, (2) optional dir prefix after '../', (3) path tail.
private val POD_LINE =
    Regex("""pod\s+['"]([^'"]+)['"]\s*,\s*(?::path\s*=>|path:)\s*['"]\.\./((?:[^'"]*/)?)([^'"/]+)['"]""")

/**
 * Detect the current shared-module name from a Podfile's
 * `pod 'X', :path => '../X'` line (either path syntax, nested paths included).
 * Only returns a name when the pod name equals the path **tail** — so
 * `'../modules/shared'` still detects `shared` — i.e. the common, unambiguous
 * case; returns null otherwise so the caller falls back to an explicit
 * `oldSharedModuleName`.
 */
internal fun detectPodSharedModule(podfileText: String): String? {
    val m = POD_LINE.find(podfileText) ?: return null
    return if (m.groupValues[1] == m.groupValues[3]) m.groupValues[1] else null
}

/**
 * Rewrite the pod line that points at [oldName] (pod name AND path tail both
 * equal to it) to [newName], preserving any directory prefix (`../modules/X`)
 * and the original `:path =>` / `path:` syntax. Other pod lines are untouched.
 */
internal fun rewritePodfileContent(text: String, oldName: String, newName: String): String =
    POD_LINE.replace(text) { m ->
        if (m.groupValues[1] != oldName || m.groupValues[3] != oldName) return@replace m.value
        val sep = if ("=>" in m.value) ":path =>" else "path:"
        val prefix = m.groupValues[2]
        "pod '$newName', $sep '../$prefix$newName'"
    }

/**
 * Rewrite plain `import <oldName>` statements to `import <newName>`.
 *
 * The trailing `(?![\w.])` guard means a submodule import (`import oldName.Foo`)
 * or a same-prefix module (`import oldNameKit`) is left untouched — only an
 * exact whole-module import is rewritten. `@testable import` and
 * `@_implementationOnly import` are intentionally not matched (the `import`
 * keyword is no longer at the line start), matching the documented narrow scope.
 */
internal fun rewriteSwiftImport(text: String, oldName: String, newName: String): String {
    val p = Regex("""(^|\n)(\s*import\s+)${Regex.escape(oldName)}(?![\w.])""")
    return p.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + newName }
}
