package io.github.yuroyami.kmpssot

/** Names emitted when identity metadata is enabled. */
private val IDENTITY_FIELD_NAMES = setOf(
    "appName",
    "versionName",
    "versionCode",
    "androidApplicationId",
    "iosBundleId",
    "locales",
)
private const val MAX_BUILD_CONFIG_FIELDS = 512
private const val MAX_BUILD_CONFIG_FIELD_TRANSPORT_CHARS = 65_536
private const val MAX_BUILD_CONFIG_FIELD_TRANSPORT_TOTAL_CHARS = 1_048_576
private const val MAX_BUILD_CONFIG_STRING_CHARS = 10_000

/**
 * A validated build-config value. The public DSL transports its canonical rendering
 * through a [org.gradle.api.provider.ListProperty] for Gradle compatibility, but values
 * stay typed while they are created and are parsed back into this model before source is
 * generated. This keeps arbitrary source fragments out of the generated object.
 */
internal sealed interface BuildConfigField {
    val name: String
    val typeName: String
    fun literal(): String

    data class StringValue(override val name: String, val value: String) : BuildConfigField {
        init {
            require(value.length <= MAX_BUILD_CONFIG_STRING_CHARS) {
                "kmpSsot { buildConfig } String field \"$name\" exceeds " +
                    "$MAX_BUILD_CONFIG_STRING_CHARS characters."
            }
        }

        override val typeName: String = "String"
        override fun literal(): String = kotlinStringLiteral(value)
    }

    data class IntValue(override val name: String, val value: Int) : BuildConfigField {
        override val typeName: String = "Int"
        override fun literal(): String = if (value == Int.MIN_VALUE) "Int.MIN_VALUE" else value.toString()
    }

    data class LongValue(override val name: String, val value: Long) : BuildConfigField {
        override val typeName: String = "Long"
        override fun literal(): String = if (value == Long.MIN_VALUE) "Long.MIN_VALUE" else "${value}L"
    }

    data class BooleanValue(override val name: String, val value: Boolean) : BuildConfigField {
        override val typeName: String = "Boolean"
        override fun literal(): String = value.toString()
    }

    data class DoubleValue(override val name: String, val value: Double) : BuildConfigField {
        init {
            require(value.isFinite()) {
                "kmpSsot { buildConfig } Double field \"$name\" must be finite; got $value."
            }
        }

        override val typeName: String = "Double"
        override fun literal(): String = value.toString()
    }

    fun renderBody(): String = "$name: $typeName = ${literal()}"
}

/**
 * Pure generator for the runtime constants object emitted into the shared module's
 * `commonMain` by [GenerateBuildConfigTask]. Inputs are validated again here so direct
 * mutation of the legacy `fields` collection cannot emit arbitrary or uncompilable
 * Kotlin source.
 */
internal fun generateBuildConfigSource(
    packageName: String,
    className: String,
    includeIdentity: Boolean,
    appName: String,
    versionName: String,
    versionCode: Int,
    androidApplicationId: String,
    iosBundleId: String,
    locales: List<String>,
    customFields: List<String>,
): String {
    invalidWorkerPackageReason(packageName)?.let { reason ->
        throw IllegalArgumentException("BuildConfig package \"$packageName\" $reason")
    }
    require(isValidKotlinIdentifier(className)) {
        "BuildConfig class name \"$className\" is not a valid Kotlin identifier."
    }

    require(customFields.size <= MAX_BUILD_CONFIG_FIELDS) {
        "kmpSsot { buildConfig } supports at most $MAX_BUILD_CONFIG_FIELDS custom fields; " +
            "got ${customFields.size}."
    }
    var transportChars = 0L
    customFields.forEach { field ->
        require(field.length <= MAX_BUILD_CONFIG_FIELD_TRANSPORT_CHARS) {
            "kmpSsot { buildConfig } field transport exceeds " +
                "$MAX_BUILD_CONFIG_FIELD_TRANSPORT_CHARS characters."
        }
        transportChars += field.length
        require(transportChars <= MAX_BUILD_CONFIG_FIELD_TRANSPORT_TOTAL_CHARS) {
            "kmpSsot { buildConfig } field transport exceeds " +
                "$MAX_BUILD_CONFIG_FIELD_TRANSPORT_TOTAL_CHARS total characters."
        }
    }
    val parsedFields = customFields.map(::parseBuildConfigField)
    val duplicateNames = parsedFields
        .groupingBy { it.name }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    require(duplicateNames.isEmpty()) {
        "kmpSsot { buildConfig } contains duplicate field name(s): ${duplicateNames.joinToString()}"
    }
    if (includeIdentity) {
        val collisions = parsedFields.map { it.name }.filter { it in IDENTITY_FIELD_NAMES }.sorted()
        require(collisions.isEmpty()) {
            "kmpSsot { buildConfig } custom field(s) collide with generated identity field(s): " +
                collisions.joinToString()
        }
    }

    val body = buildList {
        if (includeIdentity) {
            add("public const val appName: String = ${kotlinStringLiteral(appName)}")
            add("public const val versionName: String = ${kotlinStringLiteral(versionName)}")
            add("public const val versionCode: Int = $versionCode")
            add("public const val androidApplicationId: String = ${kotlinStringLiteral(androidApplicationId)}")
            add("public const val iosBundleId: String = ${kotlinStringLiteral(iosBundleId)}")
            add("public val locales: List<String> = listOf(${locales.joinToString(", ") { kotlinStringLiteral(it) }})")
        }
        parsedFields.forEach { add("public const val ${it.renderBody()}") }
    }
    val indented = body.joinToString("\n") { "    $it" }
    return """
        |/*
        | * GENERATED by kmp-ssot (io.github.yuroyami.kmpssot) — DO NOT EDIT.
        | * Owned and regenerated when inputs change by `generateKmpSsotBuildConfig`.
        | */
        |package $packageName
        |
        |/** App identity and public client configuration generated at build time. */
        |public object $className {
        |$indented
        |}
        |""".trimMargin()
}

private val IDENTIFIER_RE = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

/** A safe, dotless Kotlin identifier for an emitted declaration. */
internal fun isValidKotlinIdentifier(s: String): Boolean =
    IDENTIFIER_RE.matches(s) && s != "_" && s !in KOTLIN_HARD_KEYWORDS

/**
 * Render a field body for compatibility with the original transport format. Only the
 * five types supported by the typed DSL and a valid literal for that type are accepted.
 */
internal fun buildConfigFieldLine(type: String, name: String, literal: String): String =
    parseBuildConfigField("$name: $type = $literal").renderBody()

private val FIELD_LINE_RE = Regex(
    """^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(String|Int|Long|Boolean|Double)\s*=\s*(.+)$""",
)

/** Parse and canonicalize the legacy Gradle string transport into a typed field. */
internal fun parseBuildConfigField(line: String): BuildConfigField {
    val match = FIELD_LINE_RE.matchEntire(line)
        ?: throw IllegalArgumentException(
            "Invalid kmpSsot buildConfig field \"$line\". Use stringField/intField/longField/" +
                "booleanField/doubleField instead of adding source to fields directly."
        )
    val (name, type, literal) = match.destructured
    require(isValidKotlinIdentifier(name)) {
        "kmpSsot { buildConfig } field name \"$name\" is not a valid Kotlin identifier."
    }

    return when (type) {
        "String" -> BuildConfigField.StringValue(name, parseKotlinStringLiteral(literal))
        "Int" -> BuildConfigField.IntValue(
            name,
            if (literal == "Int.MIN_VALUE") {
                Int.MIN_VALUE
            } else {
                literal.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "BuildConfig Int field \"$name\" has an invalid literal: $literal",
                    )
            },
        )
        "Long" -> BuildConfigField.LongValue(
            name,
            if (literal == "Long.MIN_VALUE") {
                Long.MIN_VALUE
            } else {
                literal.removeSuffix("L").removeSuffix("l").toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "BuildConfig Long field \"$name\" has an invalid literal: $literal",
                    )
            },
        )
        "Boolean" -> BuildConfigField.BooleanValue(
            name,
            when (literal) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException(
                    "BuildConfig Boolean field \"$name\" must be true or false; got $literal"
                )
            },
        )
        "Double" -> {
            val value = literal.toDoubleOrNull()
                ?: throw IllegalArgumentException("BuildConfig Double field \"$name\" has an invalid literal: $literal")
            BuildConfigField.DoubleValue(name, value)
        }
        else -> error("Field regex admitted an unsupported type: $type")
    }
}

/** Render [s] as one safe Kotlin quoted-string literal. */
internal fun kotlinStringLiteral(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\b' -> append("\\b")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (
                c.isISOControl() || c.code in 0xD800..0xDFFF ||
                c == '\u2028' || c == '\u2029'
            ) {
                append("\\u%04X".format(c.code))
            } else {
                append(c)
            }
        }
    }
    append('"')
}

/** Decode the deliberately small quoted-string grammar accepted from the legacy field list. */
private fun parseKotlinStringLiteral(literal: String): String {
    require(literal.length >= 2 && literal.first() == '"' && literal.last() == '"') {
        "BuildConfig String field requires one quoted Kotlin string literal; got $literal"
    }
    val result = StringBuilder(literal.length - 2)
    var index = 1
    val end = literal.lastIndex
    while (index < end) {
        val c = literal[index++]
        require(c != '$' && !c.isISOControl()) {
            "BuildConfig String literal contains an unescaped template marker or control character."
        }
        if (c != '\\') {
            require(c != '"') { "BuildConfig String literal contains an unescaped quote." }
            result.append(c)
            continue
        }
        require(index < end) { "BuildConfig String literal ends with an incomplete escape." }
        when (val escaped = literal[index++]) {
            '\\' -> result.append('\\')
            '"' -> result.append('"')
            '$' -> result.append('$')
            'b' -> result.append('\b')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                require(index + 4 <= end) { "BuildConfig String literal has an incomplete Unicode escape." }
                val hex = literal.substring(index, index + 4)
                val code = hex.toIntOrNull(16)
                    ?: throw IllegalArgumentException("BuildConfig String literal has an invalid Unicode escape: \\u$hex")
                result.append(code.toChar())
                index += 4
            }
            else -> throw IllegalArgumentException("BuildConfig String literal has unsupported escape \\$escaped")
        }
    }
    return result.toString()
}
