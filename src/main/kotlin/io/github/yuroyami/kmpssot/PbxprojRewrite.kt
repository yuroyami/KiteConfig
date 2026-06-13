package io.github.yuroyami.kmpssot

/** Result of an in-memory pbxproj rewrite: the new text plus any non-fatal warnings. */
internal data class PbxprojResult(val text: String, val warnings: List<String>)

/**
 * Pure, side-effect-free pbxproj rewrite — extracted so it can be unit tested
 * without a Gradle project. Each argument is null when its `propagate*` toggle
 * is off or the value is unset, in which case that key is left untouched.
 *
 * Replacement is done with the lambda overload of [Regex.replace], whose return
 * value is treated as a *literal* — so app names / version strings / bundle ids
 * containing `$` or `\` no longer crash with "Illegal group reference" the way
 * the `replace(Regex, String)` overload did.
 *
 * Note (documented limitation): bare keys are replaced in **every**
 * XCBuildConfiguration section. Multi-target projects (tests, extensions,
 * widgets) all receive the same value; set `propagateBundleId = false` etc. to
 * manage divergent targets by hand.
 */
internal fun rewritePbxproj(
    original: String,
    versionName: String?,
    versionCode: Int?,
    appName: String?,
    bundleId: String?,
    locales: List<String>?,
): PbxprojResult {
    var updated = original
    val warnings = mutableListOf<String>()

    fun replaceLiteral(regex: Regex, literal: String) {
        updated = regex.replace(updated) { literal }
    }

    if (versionName != null) {
        replaceLiteral(Regex("""MARKETING_VERSION = [^;]+;"""), "MARKETING_VERSION = $versionName;")
        if (versionCode != null) {
            replaceLiteral(Regex("""CURRENT_PROJECT_VERSION = [^;]+;"""), "CURRENT_PROJECT_VERSION = $versionCode;")
        }
    }

    if (appName != null) {
        // Quote + escape so the pbxproj stays valid for names with spaces, quotes, backslashes.
        val quoted = "\"" + appName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        replaceLiteral(Regex("""INFOPLIST_KEY_CFBundleDisplayName = [^;]+;"""), "INFOPLIST_KEY_CFBundleDisplayName = $quoted;")
        replaceLiteral(Regex("""INFOPLIST_KEY_CFBundleName = [^;]+;"""), "INFOPLIST_KEY_CFBundleName = $quoted;")
        // PRODUCT_NAME is the universal knob — a custom Info.plist referencing
        // $(PRODUCT_NAME) resolves correctly once this is set.
        replaceLiteral(Regex("""PRODUCT_NAME = [^;]+;"""), "PRODUCT_NAME = $quoted;")
    }

    if (bundleId != null) {
        replaceLiteral(Regex("""PRODUCT_BUNDLE_IDENTIFIER = [^;]+;"""), "PRODUCT_BUNDLE_IDENTIFIER = $bundleId;")
    }

    if (locales != null && locales.isNotEmpty()) {
        val regions = buildList {
            add("Base")
            locales.filter { it != "Base" }.forEach { add(it) }
        }
        val replacement = regions.joinToString(
            separator = ",\n\t\t\t\t",
            prefix = "knownRegions = (\n\t\t\t\t",
            postfix = ",\n\t\t\t);",
        )
        val re = Regex("""knownRegions = \(\s*[^)]*\);""", RegexOption.DOT_MATCHES_ALL)
        if (re.containsMatchIn(updated)) {
            updated = re.replace(updated) { replacement }
        } else {
            warnings += "knownRegions block not found in pbxproj — locale list was not written. " +
                "Add a knownRegions = (...) entry to the project once, or set propagateLocaleList = false."
        }
    }

    return PbxprojResult(updated, warnings)
}
