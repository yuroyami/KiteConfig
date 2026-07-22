package io.github.yuroyami.kmpssot

/**
 * Small, fail-closed OpenStep reader for the subset of `project.pbxproj` needed by
 * the legacy in-place migration task. It tokenizes strings and comments before it
 * follows object references; setting-looking text in either can therefore never be
 * treated as project structure.
 *
 * This is deliberately not advertised as a complete pbxproj parser. Unsupported or
 * ambiguous structure is returned as an error and callers must leave the file
 * untouched. Parse uncertainty never broadens a rewrite.
 */

private const val PBX_ID_PATTERN = "[0-9A-Fa-f]{24}"
private val PBX_ID = Regex(PBX_ID_PATTERN)

private enum class TokenKind { ATOM, STRING, SYMBOL }

private data class Token(
    val kind: TokenKind,
    val value: String,
    val start: Int,
    val endExclusive: Int,
) {
    val range: IntRange get() = start until endExclusive
}

private data class LexResult(val tokens: List<Token>, val error: String? = null)

private fun lexPbx(text: String): LexResult {
    val out = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
        when {
            text[i].isWhitespace() -> i++
            text.startsWith("//", i) -> {
                i = text.indexOf('\n', i + 2).let { if (it < 0) text.length else it + 1 }
            }
            text.startsWith("/*", i) -> {
                val close = text.indexOf("*/", i + 2)
                if (close < 0) return LexResult(out, "unterminated /* ... */ comment at offset $i")
                i = close + 2
            }
            text[i] == '"' -> {
                val start = i++
                val value = StringBuilder()
                var closed = false
                while (i < text.length) {
                    when (val c = text[i++]) {
                        '\\' -> {
                            if (i >= text.length) break
                            value.append(text[i++])
                        }
                        '"' -> {
                            closed = true
                            break
                        }
                        else -> value.append(c)
                    }
                }
                if (!closed) return LexResult(out, "unterminated quoted string at offset $start")
                out += Token(TokenKind.STRING, value.toString(), start, i)
            }
            text[i] in "{}()=;," -> {
                out += Token(TokenKind.SYMBOL, text[i].toString(), i, i + 1)
                i++
            }
            else -> {
                val start = i
                while (i < text.length &&
                    !text[i].isWhitespace() &&
                    text[i] !in "{}()=;,\"" &&
                    !text.startsWith("//", i) &&
                    !text.startsWith("/*", i)
                ) {
                    i++
                }
                if (i == start) return LexResult(out, "unsupported token at offset $i")
                out += Token(TokenKind.ATOM, text.substring(start, i), start, i)
            }
        }
    }
    return LexResult(out)
}

private data class Assignment(
    val name: String,
    val tokens: List<Token>,
    val range: IntRange,
)

private data class AssignmentParseResult(
    val assignments: List<Assignment>,
    val errors: List<String>,
)

/** Parse every assignment in one dictionary body (braces excluded), or fail closed. */
private fun parseAssignments(tokens: List<Token>, context: String): AssignmentParseResult {
    val out = ArrayList<Assignment>()
    val errors = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        if (tokens[i].kind == TokenKind.SYMBOL || tokens.getOrNull(i + 1)?.value != "=") {
            errors += "$context contains an unexpected token '${tokens[i].value}' at offset ${tokens[i].start}"
            break
        }
        val start = i
        var j = i + 2
        var braces = 0
        var parens = 0
        var malformed = false
        while (j < tokens.size) {
            when (tokens[j].value) {
                "{" -> braces++
                "}" -> if (braces == 0) malformed = true else braces--
                "(" -> parens++
                ")" -> if (parens == 0) malformed = true else parens--
                ";" -> if (braces == 0 && parens == 0) break
            }
            if (malformed) break
            j++
        }
        if (malformed || j >= tokens.size || braces != 0 || parens != 0) {
            errors += "$context has a malformed or unterminated '${tokens[start].value}' assignment"
            break
        }
        val value = tokens.subList(start + 2, j)
        val structurallyValid = when {
            value.size == 1 -> value.single().kind != TokenKind.SYMBOL
            value.firstOrNull()?.value == "{" && value.lastOrNull()?.value == "}" -> true
            value.firstOrNull()?.value == "(" && value.lastOrNull()?.value == ")" -> true
            else -> false
        }
        if (!structurallyValid) {
            errors += "$context has an invalid value for '${tokens[start].value}'"
            break
        }
        out += Assignment(
            name = tokens[start].value,
            tokens = value,
            range = tokens[start].start..tokens[j].endExclusive - 1,
        )
        i = j + 1
    }
    return AssignmentParseResult(out, errors)
}

private fun Assignment.scalar(): String? = tokens.singleOrNull()?.value

private data class FlatListResult(val values: List<String>, val error: String? = null)

private fun parseFlatList(tokens: List<Token>, context: String, valuePattern: Regex? = null): FlatListResult {
    if (tokens.firstOrNull()?.value != "(" || tokens.lastOrNull()?.value != ")") {
        return FlatListResult(emptyList(), "$context is not a list")
    }
    val inner = tokens.drop(1).dropLast(1)
    if (inner.isEmpty()) return FlatListResult(emptyList())
    val values = mutableListOf<String>()
    var expectsValue = true
    inner.forEach { token ->
        if (expectsValue) {
            if (token.kind == TokenKind.SYMBOL || (valuePattern != null && !valuePattern.matches(token.value))) {
                return FlatListResult(emptyList(), "$context contains invalid list token '${token.value}'")
            }
            values += token.value
            expectsValue = false
        } else {
            if (token.value != ",") {
                return FlatListResult(emptyList(), "$context is missing a comma before '${token.value}'")
            }
            expectsValue = true
        }
    }
    // A final comma is valid OpenStep syntax; ending with a value is valid too.
    return FlatListResult(values)
}

private data class PbxObject(
    val id: String,
    val span: IntRange,
    val assignments: List<Assignment>,
)

private data class ObjectGraph(
    val objects: Map<String, PbxObject>,
    val rootProjectId: String?,
    val errors: List<String>,
)

private fun objectGraph(text: String): ObjectGraph {
    val lexed = lexPbx(text)
    lexed.error?.let { return ObjectGraph(emptyMap(), null, listOf("pbxproj parse error: $it")) }
    val t = lexed.tokens
    if (t.size < 2 || t.first().value != "{" || t.last().value != "}") {
        return ObjectGraph(emptyMap(), null, listOf("pbxproj root must be exactly one dictionary"))
    }
    var rootDepth = 0
    var rootClose = -1
    t.forEachIndexed { index, token ->
        when (token.value) {
            "{" -> rootDepth++
            "}" -> {
                rootDepth--
                if (rootDepth == 0 && rootClose < 0) rootClose = index
            }
        }
    }
    if (rootDepth != 0 || rootClose != t.lastIndex) {
        return ObjectGraph(emptyMap(), null, listOf("pbxproj root dictionary is unbalanced or has trailing structure"))
    }

    val rootParsed = parseAssignments(t.subList(1, t.lastIndex), "pbxproj root dictionary")
    if (rootParsed.errors.isNotEmpty()) return ObjectGraph(emptyMap(), null, rootParsed.errors)
    val rootAssignments = rootParsed.assignments
    val objectsAssignments = rootAssignments.filter { it.name == "objects" }
    if (objectsAssignments.size != 1) {
        return ObjectGraph(
            emptyMap(),
            null,
            listOf("pbxproj must contain exactly one direct objects dictionary; found ${objectsAssignments.size}"),
        )
    }
    val objectsTokens = objectsAssignments.single().tokens
    if (objectsTokens.firstOrNull()?.value != "{" || objectsTokens.lastOrNull()?.value != "}") {
        return ObjectGraph(emptyMap(), null, listOf("pbxproj objects value is not a dictionary"))
    }
    val rootObjectAssignments = rootAssignments.filter { it.name == "rootObject" }
    val rootProjectId = rootObjectAssignments.singleOrNull()?.scalar()
    if (rootProjectId == null || !PBX_ID.matches(rootProjectId)) {
        return ObjectGraph(emptyMap(), null, listOf("pbxproj must contain exactly one valid direct rootObject reference"))
    }

    val objectBody = objectsTokens.drop(1).dropLast(1)
    val objects = LinkedHashMap<String, PbxObject>()
    val errors = mutableListOf<String>()
    var i = 0
    while (i < objectBody.size) {
        val id = objectBody[i].value
        if (objectBody[i].kind != TokenKind.ATOM || !PBX_ID.matches(id) ||
            objectBody.getOrNull(i + 1)?.value != "=" || objectBody.getOrNull(i + 2)?.value != "{"
        ) {
            errors += "pbxproj objects dictionary contains malformed entry at offset ${objectBody[i].start}"
            break
        }
        val objectOpen = i + 2
        var depth = 1
        var objectClose = objectOpen + 1
        while (objectClose < objectBody.size && depth > 0) {
            when (objectBody[objectClose].value) {
                "{" -> depth++
                "}" -> depth--
            }
            objectClose++
        }
        if (depth != 0) {
            errors += "object $id has an unbalanced body"
            break
        }
        val closeToken = objectClose - 1
        if (objectBody.getOrNull(objectClose)?.value != ";") {
            errors += "object $id is not terminated by a semicolon"
            break
        }
        val parsed = parseAssignments(
            objectBody.subList(objectOpen + 1, closeToken),
            "pbxproj object $id",
        )
        errors += parsed.errors
        val obj = PbxObject(
            id = id,
            span = objectBody[i].start..objectBody[objectClose].endExclusive - 1,
            assignments = parsed.assignments,
        )
        if (objects.put(id, obj) != null) errors += "duplicate pbxproj object id $id"
        i = objectClose + 1
    }
    val projectObjects = objects.values.filter {
        it.assignments.singleOrNull { assignment -> assignment.name == "isa" }?.scalar() == "PBXProject"
    }
    when {
        projectObjects.size != 1 -> errors += "pbxproj must contain exactly one PBXProject object; found ${projectObjects.size}"
        projectObjects.single().id != rootProjectId -> errors +=
            "pbxproj rootObject $rootProjectId does not reference the unique PBXProject ${projectObjects.single().id}"
    }
    return ObjectGraph(objects, rootProjectId, errors)
}

/**
 * Opaque, immutable view of one parsed pbxproj.
 *
 * A rewrite can use this view for target resolution, build-setting lookup, and
 * project metadata without tokenizing or rebuilding the object graph again.
 * The graph types remain private so callers cannot bypass the fail-closed
 * structural checks.
 */
internal class PbxprojAnalysis private constructor(private val graph: ObjectGraph) {
    /** Structural parse/graph errors discovered while creating this immutable analysis. */
    internal fun structuralErrors(): List<String> = graph.errors

    internal fun objectSpans(): Map<String, IntRange> =
        graph.objects.mapValues { it.value.span }

    internal fun resolveApplicationBuildConfigSpans(
        requestedTargetNames: Set<String> = emptySet(),
    ): PbxTargetScopeResult = resolveApplicationBuildConfigSpansFromGraph(graph, requestedTargetNames)

    internal fun locateBuildSettings(
        configSpans: List<IntRange>,
        keys: Set<String>,
    ): PbxSettingsResult = locateBuildSettingsFromGraph(graph, configSpans, keys)

    internal fun locatePbxLists(key: String): Pair<List<PbxListLocation>, List<String>> =
        locatePbxListsFromGraph(graph, key)

    internal companion object {
        internal fun parse(text: String): PbxprojAnalysis = PbxprojAnalysis(objectGraph(text))
    }
}

internal fun analyzePbxproj(text: String): PbxprojAnalysis = PbxprojAnalysis.parse(text)

/** All structurally parsed top-level pbxproj object spans, primarily for diagnostics/tests. */
internal fun findObjectSpans(text: String): Map<String, IntRange> =
    analyzePbxproj(text).objectSpans()

internal data class PbxTargetScopeResult(
    val spans: List<IntRange>,
    val targetNames: List<String>,
    val errors: List<String>,
)

/**
 * Resolve build configurations for selected application targets.
 *
 * With no [requestedTargetNames], exactly one application target must exist. With
 * names, each requested name must identify exactly one application target. This
 * makes multi-app mutation an explicit choice rather than an accidental fan-out.
 */
internal fun resolveApplicationBuildConfigSpans(
    text: String,
    requestedTargetNames: Set<String> = emptySet(),
): PbxTargetScopeResult = analyzePbxproj(text).resolveApplicationBuildConfigSpans(requestedTargetNames)

private fun resolveApplicationBuildConfigSpansFromGraph(
    graph: ObjectGraph,
    requestedTargetNames: Set<String>,
): PbxTargetScopeResult {
    if (graph.errors.isNotEmpty()) return PbxTargetScopeResult(emptyList(), emptyList(), graph.errors)

    val appTargets = graph.objects.values.filter { obj ->
        obj.assignments.singleOrNull { it.name == "isa" }?.scalar() == "PBXNativeTarget" &&
            obj.assignments.singleOrNull { it.name == "productType" }?.scalar() == "com.apple.product-type.application"
    }
    val named = appTargets.map { obj ->
        obj to (obj.assignments.singleOrNull { it.name == "name" }?.scalar() ?: "<unnamed:${obj.id}>")
    }

    val errors = mutableListOf<String>()
    val selected = if (requestedTargetNames.isEmpty()) {
        when (named.size) {
            0 -> {
                errors += "pbxproj has no application target; no build settings were changed"
                emptyList()
            }
            1 -> named
            else -> {
                errors += "pbxproj has multiple application targets (${named.joinToString { it.second }}); set ios.targetNames explicitly"
                emptyList()
            }
        }
    } else {
        requestedTargetNames.mapNotNull { requested ->
            val matches = named.filter { it.second == requested }
            when (matches.size) {
                1 -> matches.single()
                0 -> { errors += "selected iOS target '$requested' is not an application target in the pbxproj"; null }
                else -> { errors += "selected iOS target '$requested' is ambiguous (${matches.size} matches)"; null }
            }
        }
    }
    if (errors.isNotEmpty()) return PbxTargetScopeResult(emptyList(), emptyList(), errors)

    val configSpans = mutableListOf<IntRange>()
    for ((target, targetName) in selected) {
        val listRefs = target.assignments.filter { it.name == "buildConfigurationList" }
        val listId = listRefs.singleOrNull()?.scalar()
        if (listId == null || !PBX_ID.matches(listId)) {
            errors += "application target '$targetName' does not have exactly one valid buildConfigurationList reference"
            continue
        }
        val list = graph.objects[listId]
        if (list == null || list.assignments.singleOrNull { it.name == "isa" }?.scalar() != "XCConfigurationList") {
            errors += "application target '$targetName' references missing/invalid XCConfigurationList $listId"
            continue
        }
        val configsAssignments = list.assignments.filter { it.name == "buildConfigurations" }
        val value = configsAssignments.singleOrNull()?.tokens
        if (value == null) {
            errors += "application target '$targetName' has an invalid buildConfigurations list"
            continue
        }
        val parsedIds = parseFlatList(value, "application target '$targetName' buildConfigurations", PBX_ID)
        if (parsedIds.error != null) {
            errors += parsedIds.error
            continue
        }
        val ids = parsedIds.values
        if (ids.isEmpty() || ids.size != ids.distinct().size) {
            errors += "application target '$targetName' has an empty or duplicate buildConfigurations list"
            continue
        }
        ids.forEach { id ->
            val config = graph.objects[id]
            if (config == null || config.assignments.singleOrNull { it.name == "isa" }?.scalar() != "XCBuildConfiguration") {
                errors += "application target '$targetName' references missing/invalid XCBuildConfiguration $id"
            } else {
                configSpans += config.span
            }
        }
    }
    if (errors.isNotEmpty()) return PbxTargetScopeResult(emptyList(), emptyList(), errors)
    return PbxTargetScopeResult(configSpans.distinct().sortedBy { it.first }, selected.map { it.second }, emptyList())
}

/** Compatibility helper retained for pure callers; ambiguity now returns no spans. */
internal fun applicationBuildConfigSpans(text: String): List<IntRange> =
    resolveApplicationBuildConfigSpans(text).spans

internal data class PbxSettingLocation(val key: String, val assignmentRange: IntRange)

internal data class PbxSettingsResult(
    val byConfig: Map<IntRange, Map<String, List<PbxSettingLocation>>>,
    val errors: List<String>,
)

/** Locate exact, direct assignments in each selected configuration's buildSettings dictionary. */
internal fun locateBuildSettings(
    text: String,
    configSpans: List<IntRange>,
    keys: Set<String>,
): PbxSettingsResult = analyzePbxproj(text).locateBuildSettings(configSpans, keys)

private fun locateBuildSettingsFromGraph(
    graph: ObjectGraph,
    configSpans: List<IntRange>,
    keys: Set<String>,
): PbxSettingsResult {
    if (graph.errors.isNotEmpty()) return PbxSettingsResult(emptyMap(), graph.errors)
    val configs = configSpans.mapNotNull { span -> graph.objects.values.singleOrNull { it.span == span } }
    if (configs.size != configSpans.size) return PbxSettingsResult(emptyMap(), listOf("selected build configuration spans could not be resolved exactly"))

    val errors = mutableListOf<String>()
    val result = LinkedHashMap<IntRange, Map<String, List<PbxSettingLocation>>>()
    for (config in configs) {
        val buildSettings = config.assignments.filter { it.name == "buildSettings" }
        val tokens = buildSettings.singleOrNull()?.tokens
        if (tokens == null || tokens.firstOrNull()?.value != "{" || tokens.lastOrNull()?.value != "}") {
            errors += "XCBuildConfiguration ${config.id} does not contain exactly one buildSettings dictionary"
            continue
        }
        val parsed = parseAssignments(
            tokens.drop(1).dropLast(1),
            "XCBuildConfiguration ${config.id} buildSettings",
        )
        errors += parsed.errors
        val settings = parsed.assignments
            .filter { it.name in keys }
            .groupBy { it.name }
            .mapValues { (key, values) -> values.map { PbxSettingLocation(key, it.range) } }
        result[config.span] = settings
    }
    return PbxSettingsResult(result, errors)
}

internal data class PbxListLocation(
    val assignmentRange: IntRange,
    val values: List<String>,
)

/** Find one direct list-valued assignment on the root PBXProject object. */
internal fun locatePbxLists(text: String, key: String): Pair<List<PbxListLocation>, List<String>> =
    analyzePbxproj(text).locatePbxLists(key)

private fun locatePbxListsFromGraph(
    graph: ObjectGraph,
    key: String,
): Pair<List<PbxListLocation>, List<String>> {
    if (graph.errors.isNotEmpty()) return emptyList<PbxListLocation>() to graph.errors
    val project = graph.rootProjectId?.let(graph.objects::get)
        ?: return emptyList<PbxListLocation>() to listOf("pbxproj root PBXProject could not be resolved")
    val assignments = project.assignments.filter { it.name == key }
    if (assignments.size > 1) {
        return emptyList<PbxListLocation>() to listOf(
            "root PBXProject contains ${assignments.size} direct $key assignments",
        )
    }
    val assignment = assignments.singleOrNull() ?: return emptyList<PbxListLocation>() to emptyList()
    val parsed = parseFlatList(assignment.tokens, "root PBXProject $key")
    parsed.error?.let { return emptyList<PbxListLocation>() to listOf(it) }
    return listOf(PbxListLocation(assignment.range, parsed.values)) to emptyList()
}
