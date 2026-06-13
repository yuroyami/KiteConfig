package io.github.yuroyami.kmpssot

/**
 * Pure helpers for the shared-module rename SSOT. Extracted from the task so the
 * tricky import-boundary cases can be unit tested.
 */

private val POD_LINE = Regex("""pod\s+['"]([^'"]+)['"],\s*:path\s*=>\s*['"]\.\.\/([^'"]+)['"]""")

/**
 * Detect the current shared-module name from a Podfile's
 * `pod 'X', :path => '../X'` line. Only returns a name when the pod name equals
 * the path tail (the common, unambiguous case); returns null otherwise so the
 * caller can fall back to an explicit `oldSharedModuleName`.
 */
internal fun detectPodSharedModule(podfileText: String): String? {
    val m = POD_LINE.find(podfileText) ?: return null
    return if (m.groupValues[1] == m.groupValues[2]) m.groupValues[1] else null
}

internal fun rewritePodfileContent(text: String, oldName: String, newName: String): String =
    text.replace(
        Regex("""pod\s+['"]${Regex.escape(oldName)}['"],\s*:path\s*=>\s*['"]\.\.\/${Regex.escape(oldName)}['"]"""),
    ) { "pod '$newName', :path => '../$newName'" }

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
