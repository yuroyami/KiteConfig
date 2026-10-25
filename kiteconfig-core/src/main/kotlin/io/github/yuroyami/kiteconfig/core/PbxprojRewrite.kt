package io.github.yuroyami.kiteconfig.core

/**
 * Result of an in-memory pbxproj rewrite. Errors mean [text] is the original byte-for-byte.
 *
 * [changedSettings] are the keys whose value changes. [reformattedSettings] keep their value
 * and change only in how they are written, such as quotes or list order.
 */
@InternalKiteCoreApi
public data class PbxprojResult(
    val text: String,
    val warnings: List<String>,
    val errors: List<String> = emptyList(),
    val selectedTargets: List<String> = emptyList(),
    val changedSettings: Set<String> = emptySet(),
    val reformattedSettings: Set<String> = emptySet(),
)

/** Values one named build configuration gets instead of the target-wide ones. Null keeps the target-wide value. */
@InternalKiteCoreApi
public data class PbxConfigurationOverride(
    val appName: String? = null,
    val bundleId: String? = null,
    val appIconName: String? = null,
)

private data class SettingRewrite(val key: String, val value: String, val literal: String, val required: Boolean = true)
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

/** The characters Xcode's own parser accepts in a value written without quotes. */
private val PBX_UNQUOTED = Regex("[A-Za-z0-9_$/:.-]+")

/**
 * The value [literal] stands for, with its quotes and escapes removed.
 * Null when Xcode could read it differently: an escape other than `\\` or `\"`, or an
 * unquoted character outside [PBX_UNQUOTED].
 */
private fun pbxScalarValue(literal: String): String? {
    if (!literal.startsWith('"')) return literal.takeIf(PBX_UNQUOTED::matches)
    if (literal.length < 2 || !literal.endsWith('"')) return null
    val body = literal.substring(1, literal.length - 1)
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i++]
        when {
            c != '\\' -> out.append(c)
            i < body.length && (body[i] == '\\' || body[i] == '"') -> out.append(body[i++])
            else -> return null
        }
    }
    return out.toString()
}

private val PBX_SAFE_BARE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")

private fun pbxNameLiteral(value: String): String =
    if (PBX_SAFE_BARE_NAME.matches(value)) value else pbxQuoted(value)

private fun validateIosAppIconName(value: String): String {
    require(value.length in 1..128) {
        "ios { appIconDirectory } must name a 1..128 character .appiconset catalog"
    }
    require(value.none { it.isISOControl() || it == '/' || it == '\\' }) {
        "ios { appIconDirectory } catalog name must not contain controls or path separators"
    }
    return value
}

/** Derive the Xcode asset-catalog build-setting value from one directory name. */
@InternalKiteCoreApi
public fun iosAppIconCatalogName(directoryName: String): String {
    require(directoryName.endsWith(".appiconset") && directoryName != ".appiconset") {
        "ios { appIconDirectory } must point to a named .appiconset directory"
    }
    return validateIosAppIconName(directoryName.removeSuffix(".appiconset"))
}

private fun validatePbxInputs(
    marketingVersion: String?,
    buildNumber: String?,
    appName: String?,
    bundleId: String?,
    appIconName: String?,
    configurationOverrides: Map<String, PbxConfigurationOverride>,
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
    configurationOverrides.forEach { (configuration, override) ->
        if (configuration.isBlank() || configuration.any(Char::isISOControl)) {
            add("build configuration names must be non-blank and free of control characters")
        }
        validate(override.appName, ::validateAppName)
        validate(override.bundleId, ::validateAppleBundleId)
        validate(override.appIconName, ::validateIosAppIconName)
    }
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
 * [configurationOverrides] give the configurations they name their own values,
 * such as `Debug`. Naming a configuration the target does not have is an error.
 *
 * [allowUnscopedFragment] exists only for focused tests of serialization. Callers
 * handling a real project file must leave it false.
 */
@InternalKiteCoreApi
public fun rewritePbxproj(
    original: String,
    marketingVersion: String?,
    buildNumber: String?,
    appName: String?,
    bundleId: String?,
    locales: List<String>?,
    targetNames: Set<String> = emptySet(),
    allowUnscopedFragment: Boolean = false,
    appIconName: String? = null,
    configurationOverrides: Map<String, PbxConfigurationOverride> = emptyMap(),
    analysisFactory: (String) -> PbxprojAnalysis = ::analyzePbxproj,
): PbxprojResult {
    val warnings = mutableListOf<String>()
    val errors = validatePbxInputs(marketingVersion, buildNumber, appName, bundleId, appIconName, configurationOverrides)
        .toMutableList()
    val changedKeys = linkedSetOf<String>()
    val reformattedKeys = linkedSetOf<String>()
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
    // One builder serves the target-wide values and each override, so an override
    // cannot spell a setting differently from the rest of the target.
    fun settingRewrites(appName: String?, bundleId: String?, appIconName: String?): List<SettingRewrite> = buildList {
        if (marketingVersion != null) add(SettingRewrite("MARKETING_VERSION", marketingVersion, "MARKETING_VERSION = $marketingVersion;"))
        if (buildNumber != null) add(SettingRewrite("CURRENT_PROJECT_VERSION", buildNumber, "CURRENT_PROJECT_VERSION = $buildNumber;"))
        if (appName != null) {
            val quoted = runCatching { pbxQuoted(appName) }.getOrElse {
                errors += "iOS appName cannot be encoded safely: ${it.message}"
                "\"INVALID\""
            }
            add(SettingRewrite("PRODUCT_NAME", appName, "PRODUCT_NAME = $quoted;"))
            // These keys are absent in projects that use a source Info.plist. They
            // are updated when present but their absence is not structural failure.
            add(SettingRewrite("INFOPLIST_KEY_CFBundleDisplayName", appName, "INFOPLIST_KEY_CFBundleDisplayName = $quoted;", required = false))
            add(SettingRewrite("INFOPLIST_KEY_CFBundleName", appName, "INFOPLIST_KEY_CFBundleName = $quoted;", required = false))
        }
        if (bundleId != null) add(SettingRewrite("PRODUCT_BUNDLE_IDENTIFIER", bundleId, "PRODUCT_BUNDLE_IDENTIFIER = $bundleId;"))
        if (appIconName != null) {
            add(
                SettingRewrite(
                    "ASSETCATALOG_COMPILER_APPICON_NAME",
                    appIconName,
                    "ASSETCATALOG_COMPILER_APPICON_NAME = ${pbxNameLiteral(appIconName)};",
                ),
            )
        }
    }
    val rewrites = if (errors.isNotEmpty()) emptyList() else settingRewrites(appName, bundleId, appIconName)
    fun rewritesFor(override: PbxConfigurationOverride?): List<SettingRewrite> =
        if (override == null) {
            rewrites
        } else {
            settingRewrites(
                appName = override.appName ?: appName,
                bundleId = override.bundleId ?: bundleId,
                appIconName = override.appIconName ?: appIconName,
            )
        }

    // A key counts as changed only when its value moves. The same value spelled another way is reformatting.
    fun planSetting(rewrite: SettingRewrite, range: IntRange, valueRange: IntRange?) {
        replacements += TextReplacement(range, rewrite.literal)
        // An escape in the key could spell a different key, so only a plain key can keep its value.
        val sameValue = valueRange != null &&
            '\\' !in original.substring(range.first, valueRange.first) &&
            pbxScalarValue(original.substring(valueRange).trim()) == rewrite.value
        when {
            original.substring(range) == rewrite.literal -> {}
            sameValue -> reformattedKeys += rewrite.key
            else -> changedKeys += rewrite.key
        }
    }

    var selectedTargets = emptyList<String>()
    if ((rewrites.isNotEmpty() || configurationOverrides.isNotEmpty()) && errors.isEmpty()) {
        if (allowUnscopedFragment) {
            if (configurationOverrides.isNotEmpty()) errors += "configuration overrides need a whole project file, not a settings fragment"
            rewrites.forEach { rewrite ->
                val matches = unscopedSettingRegex(rewrite.key).findAll(original).toList()
                when {
                    matches.size == 1 -> planSetting(rewrite, matches.single().range, matches.single().groups[1]?.range)
                    matches.isEmpty() && rewrite.required -> errors += "settings fragment is missing required ${rewrite.key} assignment"
                    matches.isEmpty() -> warnings += "settings fragment has no ${rewrite.key} assignment; it was not inserted"
                    else -> errors += "settings fragment has ${matches.size} ${rewrite.key} assignments; refusing an ambiguous rewrite"
                }
            }
        } else {
            val scope = analysis.resolveApplicationBuildConfigSpans(targetNames)
            errors += scope.errors
            selectedTargets = scope.targetNames
            if (errors.isEmpty() && configurationOverrides.isNotEmpty()) {
                val present = scope.configurationNames.values.toSet()
                val missing = configurationOverrides.keys.filterNot { it in present }
                if (missing.isNotEmpty()) {
                    errors += "selected application target(s) have no build configuration named " +
                        missing.joinToString { "'$it'" } +
                        "; ios { debug { configurations(...) } } must name configurations that exist"
                }
            }
            if (errors.isEmpty()) {
                val keys = (rewrites + configurationOverrides.values.flatMap(::rewritesFor)).mapTo(linkedSetOf()) { it.key }
                val located = analysis.locateBuildSettings(scope.spans, keys)
                errors += located.errors
                if (errors.isEmpty()) {
                    for ((configSpan, keysInConfig) in located.byConfig) {
                        val override = scope.configurationNames[configSpan]?.let(configurationOverrides::get)
                        for (rewrite in rewritesFor(override)) {
                            val matches = keysInConfig[rewrite.key].orEmpty()
                            when {
                                matches.size == 1 -> {
                                    val match = matches.single()
                                    planSetting(rewrite, match.assignmentRange, match.valueRange)
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
                    val current = original.substring(location.assignmentRange)
                    when {
                        current == replacement -> {}
                        // Regions form a set, so order alone is reformatting. An escape or odd spelling may hide another region.
                        '\\' in current || location.values.any { !PBX_UNQUOTED.matches(it) } ||
                            merged.toSet() != location.values.toSet() -> changedKeys += "knownRegions"
                        else -> reformattedKeys += "knownRegions"
                    }
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
    return PbxprojResult(updated, warnings.distinct(), emptyList(), selectedTargets, changedKeys, reformattedKeys - changedKeys)
}

/** Strict helper for the explicitly opted-in synthetic fragment mode only. Group 1 is the value. */
private fun unscopedSettingRegex(key: String): Regex =
    Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;\"\\r\\n]+);")
