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
 * Every local dev-pod whose pod name equals its path **tail** (so
 * `'../modules/shared'` yields `shared`), in Podfile order, de-duplicated. These
 * are the candidate "old shared module" names. More than one means the Podfile
 * has several local pods and auto-detection can't safely pick — the caller must
 * then require an explicit `oldSharedModuleName` rather than guess and risk
 * renaming an unrelated pod.
 */
internal fun detectPodSharedModuleCandidates(podfileText: String): List<String> =
    POD_LINE.findAll(podfileText)
        .filter { it.groupValues[1] == it.groupValues[3] }
        .map { it.groupValues[1] }
        .distinct()
        .toList()

/**
 * The single unambiguous shared-module name from a Podfile, or null when there
 * is none — or, deliberately, when there is **more than one** local dev-pod (the
 * caller warns and asks for an explicit `oldSharedModuleName` instead of picking
 * the first and corrupting a sibling pod's references).
 */
internal fun detectPodSharedModule(podfileText: String): String? =
    detectPodSharedModuleCandidates(podfileText).singleOrNull()

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
