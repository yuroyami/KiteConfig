package io.github.yuroyami.kmpssot

/** Result of an in-memory pbxproj rewrite. Errors mean [text] is the original byte-for-byte. */
internal data class PbxprojResult(
    val text: String,
    val warnings: List<String>,
    val errors: List<String> = emptyList(),
    val selectedTargets: List<String> = emptyList(),
    val changedSettings: Set<String> = emptySet(),
)

private data class SettingRewrite(val key: String, val literal: String, val required: Boolean = true)
private data class TextReplacement(val range: IntRange, val text: String)

private fun pbxQuoted(value: String): String {
    require(value.none { it.code < 0x20 || it == '\u007f' }) { "control characters are not valid in pbxproj strings" }
    return buildString(value.length + 2) {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(c)
            }
        }
        append('"')
    }
}

private val PBX_SAFE_BARE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")

private fun pbxNameLiteral(value: String): String =
    if (PBX_SAFE_BARE_NAME.matches(value)) value else pbxQuoted(value)

private fun validateIosAppIconName(value: String): String {
    require(value.length in 1..128) {
        "iosAppIconDirectory must name a 1..128 character .appiconset catalog"
    }
    require(value.none { it.isISOControl() || it == '/' || it == '\\' }) {
        "iosAppIconDirectory catalog name must not contain controls or path separators"
    }
    return value
}

/** Derive the Xcode asset-catalog build-setting value from one directory name. */
internal fun iosAppIconCatalogName(directoryName: String): String {
    require(directoryName.endsWith(".appiconset") && directoryName != ".appiconset") {
        "iosAppIconDirectory must point to a named .appiconset directory"
    }
    return validateIosAppIconName(directoryName.removeSuffix(".appiconset"))
}

private fun validatePbxInputs(
    marketingVersion: String?,
    buildNumber: String?,
    appName: String?,
    bundleId: String?,
    appIconName: String?,
): List<String> = buildList {
    fun validate(value: String?, check: (String) -> String) {
        if (value == null) return
        runCatching { check(value) }.exceptionOrNull()?.let { failure ->
            add(failure.message?.let { diagnosticSafeText(it) } ?: "invalid pbxproj migration input")
        }
    }

    validate(marketingVersion, ::validateAppleMarketingVersion)
    validate(buildNumber, ::validateAppleBuildNumber)
    validate(appName, ::validateAppName)
    validate(bundleId, ::validateAppleBundleId)
    validate(appIconName, ::validateIosAppIconName)
}

/**
 * Pure fail-closed pbxproj migration.
 *
 * Build settings are changed only inside configurations belonging to an explicitly
 * selected application target, or the sole application target when [targetNames] is
 * empty. Missing, duplicate, malformed, or ambiguous structure produces errors and
 * returns [original] unchanged. When [appIconName] is present, its existing
 * `ASSETCATALOG_COMPILER_APPICON_NAME` assignment is required in every selected
 * configuration and aligned with the installed catalog. There is no production
 * global fallback and no speculative setting insertion.
 *
 * [allowUnscopedFragment] exists only for focused tests of serialization. Callers
 * handling a real project file must leave it false.
 */
internal fun rewritePbxproj(
    original: String,
    marketingVersion: String?,
    buildNumber: String?,
    appName: String?,
    bundleId: String?,
    locales: List<String>?,
    targetNames: Set<String> = emptySet(),
    allowUnscopedFragment: Boolean = false,
    appIconName: String? = null,
    analysisFactory: (String) -> PbxprojAnalysis = ::analyzePbxproj,
): PbxprojResult {
    val warnings = mutableListOf<String>()
    val errors = validatePbxInputs(marketingVersion, buildNumber, appName, bundleId, appIconName).toMutableList()
    val changedKeys = linkedSetOf<String>()
    val replacements = mutableListOf<TextReplacement>()
    val analysis by lazy(LazyThreadSafetyMode.NONE) { analysisFactory(original) }
    if (!allowUnscopedFragment && errors.isEmpty()) {
        // Diagnostics must validate the project graph even when no setting is
        // currently configured for mutation. Keeping this on the same opaque
        // analysis still guarantees exactly one parse per rewrite invocation.
        errors += analysis.structuralErrors()
    }

    // Do not derive literals from values that already failed centralized
    // validation. Besides avoiding duplicate diagnostics, this keeps malformed
    // or oversized input out of quoting/serialization paths entirely.
    val rewrites = if (errors.isNotEmpty()) emptyList() else buildList {
        if (marketingVersion != null) add(SettingRewrite("MARKETING_VERSION", "MARKETING_VERSION = $marketingVersion;"))
        if (buildNumber != null) add(SettingRewrite("CURRENT_PROJECT_VERSION", "CURRENT_PROJECT_VERSION = $buildNumber;"))
        if (appName != null) {
            val quoted = runCatching { pbxQuoted(appName) }.getOrElse {
                errors += "iOS appName cannot be encoded safely: ${it.message}"
                "\"INVALID\""
            }
            add(SettingRewrite("PRODUCT_NAME", "PRODUCT_NAME = $quoted;"))
            // These keys are absent in projects that use a source Info.plist. They
            // are updated when present but their absence is not structural failure.
            add(SettingRewrite("INFOPLIST_KEY_CFBundleDisplayName", "INFOPLIST_KEY_CFBundleDisplayName = $quoted;", required = false))
            add(SettingRewrite("INFOPLIST_KEY_CFBundleName", "INFOPLIST_KEY_CFBundleName = $quoted;", required = false))
        }
        if (bundleId != null) add(SettingRewrite("PRODUCT_BUNDLE_IDENTIFIER", "PRODUCT_BUNDLE_IDENTIFIER = $bundleId;"))
        if (appIconName != null) {
            add(
                SettingRewrite(
                    "ASSETCATALOG_COMPILER_APPICON_NAME",
                    "ASSETCATALOG_COMPILER_APPICON_NAME = ${pbxNameLiteral(appIconName)};",
                ),
            )
        }
    }

    var selectedTargets = emptyList<String>()
    if (rewrites.isNotEmpty() && errors.isEmpty()) {
        if (allowUnscopedFragment) {
            rewrites.forEach { rewrite ->
                val matches = unscopedSettingRegex(rewrite.key).findAll(original).toList()
                when {
                    matches.size == 1 -> {
                        replacements += TextReplacement(matches.single().range, rewrite.literal)
                        changedKeys += rewrite.key
                    }
                    matches.isEmpty() && rewrite.required -> errors += "settings fragment is missing required ${rewrite.key} assignment"
                    matches.isEmpty() -> warnings += "settings fragment has no ${rewrite.key} assignment; it was not inserted"
                    else -> errors += "settings fragment has ${matches.size} ${rewrite.key} assignments; refusing an ambiguous rewrite"
                }
            }
        } else {
            val scope = analysis.resolveApplicationBuildConfigSpans(targetNames)
            errors += scope.errors
            selectedTargets = scope.targetNames
            if (errors.isEmpty()) {
                val located = analysis.locateBuildSettings(scope.spans, rewrites.mapTo(linkedSetOf()) { it.key })
                errors += located.errors
                if (errors.isEmpty()) {
                    for ((configSpan, keys) in located.byConfig) {
                        for (rewrite in rewrites) {
                            val matches = keys[rewrite.key].orEmpty()
                            when {
                                matches.size == 1 -> {
                                    replacements += TextReplacement(matches.single().assignmentRange, rewrite.literal)
                                    changedKeys += rewrite.key
                                }
                                matches.isEmpty() && rewrite.required -> errors +=
                                    "selected XCBuildConfiguration at ${configSpan.first} is missing required ${rewrite.key}; no changes were made"
                                matches.isEmpty() -> warnings +=
                                    "selected XCBuildConfiguration at ${configSpan.first} has no ${rewrite.key}; the optional setting was not inserted"
                                else -> errors +=
                                    "selected XCBuildConfiguration at ${configSpan.first} has ${matches.size} ${rewrite.key} assignments"
                            }
                        }
                    }
                }
            }
        }
    }

    if (locales != null && errors.isEmpty()) {
        val desired = runCatching { canonicalizeLocales(locales) }.getOrElse {
            errors += (it.message ?: "invalid iOS locale list")
            emptyList()
        }
        if (errors.isEmpty() && desired.isNotEmpty()) {
            val (lists, parseErrors) = analysis.locatePbxLists("knownRegions")
            errors += parseErrors
            when (lists.size) {
                0 -> errors += "pbxproj has no structurally valid knownRegions list; locale metadata was not changed"
                1 -> {
                    val location = lists.single()
                    val merged = linkedSetOf<String>().apply {
                        add("Base")
                        addAll(location.values.filterNot { it == "Base" })
                        addAll(desired)
                    }.toList()
                    val lineStart = original.lastIndexOf('\n', location.assignmentRange.first - 1).let { if (it < 0) 0 else it + 1 }
                    val baseIndent = original.substring(lineStart, location.assignmentRange.first).takeWhile { it == ' ' || it == '\t' }
                    val entryIndent = baseIndent + "\t"
                    val replacement = merged.joinToString(
                        separator = ",\n$entryIndent",
                        prefix = "knownRegions = (\n$entryIndent",
                        postfix = ",\n$baseIndent);",
                    ) { region -> if (Regex("[A-Za-z0-9_.-]+").matches(region)) region else pbxQuoted(region) }
                    replacements += TextReplacement(location.assignmentRange, replacement)
                    changedKeys += "knownRegions"
                }
                else -> errors += "pbxproj has ${lists.size} knownRegions lists; refusing an ambiguous project-level rewrite"
            }
        }
    }

    if (errors.isNotEmpty()) {
        return PbxprojResult(original, warnings, errors.distinct(), selectedTargets, emptySet())
    }

    val overlaps = replacements.sortedBy { it.range.first }.zipWithNext().filter { (a, b) -> a.range.last >= b.range.first }
    if (overlaps.isNotEmpty()) {
        return PbxprojResult(original, warnings, listOf("internal rewrite plan contained overlapping pbxproj edits"), selectedTargets)
    }
    var updated = original
    replacements.sortedByDescending { it.range.first }.forEach { replacement ->
        updated = updated.replaceRange(replacement.range, replacement.text)
    }
    return PbxprojResult(updated, warnings.distinct(), emptyList(), selectedTargets, changedKeys)
}

/** Strict helper for the explicitly opted-in synthetic fragment mode only. */
private fun unscopedSettingRegex(key: String): Regex =
    Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^;\"\\r\\n]+);")
