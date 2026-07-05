package io.github.yuroyami.kmpssot

/** Result of an in-memory pbxproj rewrite: the new text plus any non-fatal warnings. */
internal data class PbxprojResult(val text: String, val warnings: List<String>)

/** A single build-setting rewrite: the left-anchored key matcher and its literal replacement. */
private data class SettingRewrite(val regex: Regex, val literal: String)

/**
 * A build setting `KEY = <value>;` where:
 *  - KEY is not preceded by an identifier char, so `PRODUCT_NAME` never matches
 *    inside `FOO_PRODUCT_NAME` (and likewise for every key); and
 *  - <value> is either a double-quoted string (which may legally contain ';') or
 *    a run of bare chars that stops at ';', '"' or a newline — so a value missing
 *    its terminating ';' can't swallow later lines, and a quoted ';' can't split
 *    the value mid-string.
 */
private fun settingRegex(key: String): Regex =
    Regex("""(?<![A-Za-z0-9_])$key = (?:"(?:[^"\\]|\\.)*"|[^;"\n]+);""")

/**
 * Pure, side-effect-free pbxproj rewrite — extracted so it can be unit tested
 * without a Gradle project. Each argument is null when its `propagate*` toggle
 * is off or the value is unset, in which case that key is left untouched.
 *
 * Replacement uses the lambda overload of [Regex.replace], whose return value is
 * treated as a *literal* — so app names / versions / bundle ids containing `$` or
 * `\` don't crash with "Illegal group reference".
 *
 * **Target scoping:** build-setting keys (`PRODUCT_NAME`, `PRODUCT_BUNDLE_IDENTIFIER`,
 * `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_*`) are rewritten
 * only inside the **application target's** `XCBuildConfiguration` objects (see
 * [applicationBuildConfigSpans]), so test / extension / widget targets keep their
 * own names and distinct bundle ids. When no application target is found the
 * rewrite falls back to global replacement; if the input looked like a real
 * pbxproj (it contains `isa =`) a warning is emitted so multi-target users verify
 * their other targets. `knownRegions` is a single project-level block and is
 * always rewritten globally.
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

    val rewrites = buildList {
        if (versionName != null) {
            add(SettingRewrite(settingRegex("MARKETING_VERSION"), "MARKETING_VERSION = $versionName;"))
        }
        // Independent of versionName: a lone versionCodeOverride still writes here.
        if (versionCode != null) {
            add(SettingRewrite(settingRegex("CURRENT_PROJECT_VERSION"), "CURRENT_PROJECT_VERSION = $versionCode;"))
        }
        if (appName != null) {
            // Quote + escape so the pbxproj stays valid for names with spaces, quotes, backslashes.
            val quoted = "\"" + appName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            add(SettingRewrite(settingRegex("INFOPLIST_KEY_CFBundleDisplayName"), "INFOPLIST_KEY_CFBundleDisplayName = $quoted;"))
            add(SettingRewrite(settingRegex("INFOPLIST_KEY_CFBundleName"), "INFOPLIST_KEY_CFBundleName = $quoted;"))
            // PRODUCT_NAME is the universal knob — a custom Info.plist referencing
            // $(PRODUCT_NAME) resolves correctly once this is set.
            add(SettingRewrite(settingRegex("PRODUCT_NAME"), "PRODUCT_NAME = $quoted;"))
        }
        if (bundleId != null) {
            add(SettingRewrite(settingRegex("PRODUCT_BUNDLE_IDENTIFIER"), "PRODUCT_BUNDLE_IDENTIFIER = $bundleId;"))
        }
    }

    if (rewrites.isNotEmpty()) {
        val spans = applicationBuildConfigSpans(updated)
        if (spans.isNotEmpty()) {
            // Splice each app-target build-config span back-to-front so earlier
            // spans' offsets stay valid as later ones change length.
            for (span in spans.sortedByDescending { it.first }) {
                var segment = updated.substring(span)
                for (rw in rewrites) segment = rw.regex.replace(segment) { rw.literal }
                updated = updated.substring(0, span.first) + segment + updated.substring(span.last + 1)
            }
        } else {
            for (rw in rewrites) updated = rw.regex.replace(updated) { rw.literal }
            // A bare settings fragment (no object graph) is the test/simple case —
            // silent. A real pbxproj with no application target is worth flagging.
            if (updated.contains("isa =")) {
                warnings += "pbxproj: no application target (com.apple.product-type.application) " +
                    "found — build settings were applied globally. Verify any test/extension " +
                    "targets did not inherit the app's PRODUCT_NAME / bundle id."
            }
        }
    }

    if (locales != null && locales.isNotEmpty()) {
        // This is the iOS boundary: map Android qualifier tags (pt-rBR, b+sr+Latn)
        // to the Apple knownRegions form (pt-BR, sr-Latn).
        val regions = buildList {
            add("Base")
            locales.filter { it != "Base" }.forEach { add(androidTagToAppleTag(it)) }
        }
        // Reuse the indentation of the existing block so the diff stays clean;
        // fall back to 4 tabs (Xcode's default nesting) when it can't be sniffed.
        val existing = Regex("""knownRegions = \(\s*\n(\s*)""").find(updated)?.groupValues?.get(1) ?: "\t\t\t\t"
        val closeIndent = existing.dropLast(1).ifEmpty { "\t\t\t" }
        val replacement = regions.joinToString(
            separator = ",\n$existing",
            prefix = "knownRegions = (\n$existing",
            postfix = ",\n$closeIndent);",
        )
        // Region tokens only (bare/quoted identifiers, commas, dots, hyphens,
        // whitespace incl. newlines). A stray ')' — e.g. inside an injected
        // comment — makes the whole match fail, so we emit the not-found warning
        // instead of truncating the block at the first ')' and corrupting it.
        val re = Regex("""knownRegions = \([\sA-Za-z0-9_,"'.\-]*\);""")
        if (re.containsMatchIn(updated)) {
            updated = re.replace(updated) { replacement }
        } else {
            warnings += "knownRegions block not found in pbxproj — locale list was not written. " +
                "Add a knownRegions = (...) entry to the project once, or set propagateLocaleList = false."
        }
    }

    return PbxprojResult(updated, warnings)
}
