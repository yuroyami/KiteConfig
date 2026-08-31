package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import java.util.Locale

/**
 * Locale values use the platform-resource-compatible BCP-47 subset: a 2–3 letter
 * language, optional four-letter script, optional alpha-2/numeric-3 region, and
 * optional variants. Extensions, private-use, grandfathered tags, and other valid
 * general-purpose BCP-47 forms are intentionally rejected because they do not map
 * consistently to Android resource qualifiers and Xcode known regions. Legacy
 * Android qualifiers remain accepted at the boundary and are converted immediately.
 */
private val ANDROID_REGION = Regex("""^([a-zA-Z]{2,3})-r([a-zA-Z]{2})$""")
private val ANDROID_BCP47 = Regex("""^b\+([A-Za-z0-9]+(?:\+[A-Za-z0-9]+)*)$""")
private val SUPPORTED_BCP47_SHAPE = Regex(
    """^[A-Za-z]{2,3}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|\d{3}))?(?:-(?:[A-Za-z0-9]{5,8}|\d[A-Za-z0-9]{3}))*$"""
)
private val GRANDFATHERED_BCP47_TAGS = setOf(
    "art-lojban", "cel-gaulish", "en-gb-oed", "i-ami", "i-bnn", "i-default",
    "i-enochian", "i-hak", "i-klingon", "i-lux", "i-mingo", "i-navajo", "i-pwn",
    "i-tao", "i-tay", "i-tsu", "no-bok", "no-nyn", "sgn-be-fr", "sgn-be-nl",
    "sgn-ch-de", "zh-guoyu", "zh-hakka", "zh-min", "zh-min-nan", "zh-xiang",
)
private const val MAX_LOCALE_TAG_CHARS = 255
private const val MAX_CONFIGURED_LOCALES = 1_000

/**
 * Android 8's legacy `resourceConfigurations` collection mixes locales with
 * every other resource qualifier. `car` is especially ambiguous: it is both a
 * valid ISO 639-3 language and Android's automotive UI-mode qualifier. Never
 * infer that an existing bare ambiguous token is a locale; the BCP-47 `b+...`
 * spelling remains unambiguous for a configured locale.
 */
private val ANDROID_AMBIGUOUS_NON_LOCALE_QUALIFIERS = setOf("car")

/** Canonical supported resource-locale form, or null when [tag] is outside that subset. */
internal fun canonicalLocaleTag(tag: String): String? {
    if (tag.isEmpty() || tag.length > MAX_LOCALE_TAG_CHARS) return null
    val normalizedInput = when (val region = ANDROID_REGION.matchEntire(tag)) {
        null -> ANDROID_BCP47.matchEntire(tag)
            ?.groupValues
            ?.get(1)
            ?.replace('+', '-')
            ?: tag
        else -> {
            val match = region
            "${match.groupValues[1]}-${match.groupValues[2]}"
        }
    }
    if (!SUPPORTED_BCP47_SHAPE.matches(normalizedInput)) return null
    if (normalizedInput.lowercase(Locale.ROOT) in GRANDFATHERED_BCP47_TAGS) return null
    val subtags = normalizedInput.split('-')
    var variantStart = 1
    if (subtags.getOrNull(variantStart)?.let { it.length == 4 && it.all(Char::isLetter) } == true) {
        variantStart += 1
    }
    if (
        subtags.getOrNull(variantStart)?.let {
            (it.length == 2 && it.all(Char::isLetter)) ||
                (it.length == 3 && it.all(Char::isDigit))
        } == true
    ) {
        variantStart += 1
    }
    val variants = subtags.drop(variantStart).map { it.lowercase(Locale.ROOT) }
    if (variants.size != variants.toSet().size) return null
    val canonical = Locale.forLanguageTag(normalizedInput).toLanguageTag()
    return canonical.takeUnless {
        it.equals("und", ignoreCase = true) || !SUPPORTED_BCP47_SHAPE.matches(it)
    }
}

/** Validate, canonicalize, and de-duplicate after conversion. */
internal fun canonicalizeLocales(tags: Iterable<String>): List<String> {
    val invalid = mutableListOf<String>()
    val canonical = LinkedHashSet<String>()
    var count = 0
    tags.forEach { raw ->
        count += 1
        if (count > MAX_CONFIGURED_LOCALES) {
            throw GradleException(
                "kiteConfig { locales } supports at most $MAX_CONFIGURED_LOCALES entries.",
            )
        }
        val value = canonicalLocaleTag(raw)
        if (value == null) invalid += raw else canonical += value
    }
    if (invalid.isNotEmpty()) {
        val displayed = invalid.take(20).joinToString(", ") { diagnosticSafeText(it, 256) }
        val omitted = invalid.size - minOf(invalid.size, 20)
        throw GradleException(
            "kiteConfig { locales } contains invalid locale tag(s): $displayed" +
                (if (omitted > 0) ", … and $omitted more" else "") + ". " +
                "Use supported resource-locale tags such as en, pt-BR, or sr-Latn; " +
                "extensions and private-use tags cannot map consistently across Android and Xcode."
        )
    }
    return canonical.toList()
}

/** Convert a canonical/legacy value to Apple's BCP-47-style region token. */
internal fun androidTagToAppleTag(tag: String): String = canonicalLocaleTag(tag) ?: tag

/** Convert a canonical supported resource-locale tag to an Android resource qualifier. */
internal fun bcp47ToAndroidQualifier(tag: String): String {
    val canonical = canonicalLocaleTag(tag)
        ?: throw GradleException("Invalid BCP-47 locale tag: ${diagnosticSafeText(tag, 256)}")
    val locale = Locale.forLanguageTag(canonical)
    val simpleRegion = locale.script.isEmpty() && locale.variant.isEmpty() &&
        locale.country.length == 2 && locale.country.all(Char::isLetter)
    val ambiguousLanguageOnly = canonical == locale.language &&
        canonical.lowercase(Locale.ROOT) in ANDROID_AMBIGUOUS_NON_LOCALE_QUALIFIERS
    return when {
        simpleRegion -> "${locale.language}-r${locale.country}"
        ambiguousLanguageOnly -> "b+${canonical.replace('-', '+')}"
        canonical == locale.language -> locale.language
        else -> "b+${canonical.replace('-', '+')}"
    }
}

/** True only for resource-directory suffixes that can be safely canonicalized. */
internal fun looksLikeLocaleQualifier(tag: String): Boolean = canonicalLocaleTag(tag) != null

/**
 * True only when an AGP 8 `resourceConfigurations` entry can be removed as a
 * locale without also deleting a valid non-locale Android qualifier.
 */
internal fun looksLikeUnambiguousLocaleQualifier(tag: String): Boolean =
    tag.lowercase(Locale.ROOT) !in ANDROID_AMBIGUOUS_NON_LOCALE_QUALIFIERS &&
        looksLikeLocaleQualifier(tag)
