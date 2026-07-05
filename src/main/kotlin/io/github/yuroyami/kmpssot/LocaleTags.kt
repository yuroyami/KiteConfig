package io.github.yuroyami.kmpssot

/**
 * Compose/Android resource directories use Android locale qualifiers
 * (`values-pt-rBR`, `values-b+sr+Latn`); iOS `knownRegions` wants the BCP-47-ish
 * form (`pt-BR`, `sr-Latn`). These pure helpers bridge the two and screen out
 * `values-*` dirs that aren't locales at all — applied at the iOS boundary only;
 * Android keeps its own qualifier form.
 */

private val ANDROID_REGION = Regex("""^([a-z]{2,3})-r([A-Z]{2})$""")
private val ANDROID_BCP47 = Regex("""^b\+(.+)$""")
private val PLAIN_LANG = Regex("""^[a-z]{2,3}$""")

/**
 * Convert an Android locale qualifier tag to the Apple `knownRegions` form:
 * `pt-rBR` → `pt-BR`, `b+sr+Latn` → `sr-Latn`. A plain language (`en`) or an
 * already-Apple tag passes through unchanged.
 */
internal fun androidTagToAppleTag(tag: String): String {
    ANDROID_REGION.matchEntire(tag)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
    ANDROID_BCP47.matchEntire(tag)?.let { return it.groupValues[1].replace('+', '-') }
    return tag
}

/**
 * Whether a `values-<tag>` suffix is actually a locale qualifier rather than some
 * other resource qualifier (`night`, `v26`, `land`, `sw600dp`, `xxhdpi`) or junk.
 * Conservative — only the language / language-region / BCP-47 shapes the plugin
 * can map safely — so auto-detection never propagates a non-locale as a locale.
 */
internal fun looksLikeLocaleQualifier(tag: String): Boolean =
    PLAIN_LANG.matches(tag) || ANDROID_REGION.matches(tag) || ANDROID_BCP47.matches(tag)
