package io.github.yuroyami.kmpssot

import java.awt.Color
import java.awt.image.BufferedImage

private val HEX_RE = Regex("""#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{8}""")

/** Throws if [hex] is not `#RRGGBB` or `#AARRGGBB`. */
internal fun validateLogoBackgroundColorHex(hex: String) {
    require(HEX_RE.matches(hex)) {
        "appLogoBackgroundColor must be #RRGGBB or #AARRGGBB (Android convention, alpha first), got: $hex"
    }
}

/** Parse a pre-validated `#RRGGBB` or `#AARRGGBB` hex string into an AWT [Color]. */
internal fun parseLogoBackgroundColor(hex: String): Color {
    validateLogoBackgroundColorHex(hex)
    val clean = hex.removePrefix("#")
    return if (clean.length == 6) {
        Color(clean.toInt(16))
    } else {
        // AARRGGBB
        val argb = clean.toLong(16)
        Color(
            ((argb shr 16) and 0xFF).toInt(),
            ((argb shr 8)  and 0xFF).toInt(),
            ( argb         and 0xFF).toInt(),
            ((argb shr 24) and 0xFF).toInt(),
        )
    }
}

/**
 * Composite a (possibly translucent) [c] over opaque white, returning an opaque
 * colour. Used so a semi-transparent `appLogoBackgroundColor` renders the same
 * on Android (which would otherwise keep the alpha) as on iOS (which flattens
 * against white because the App Store rejects alpha icons).
 */
internal fun flattenOverWhite(c: Color): Color {
    if (c.alpha == 255) return c
    val a = c.alpha / 255.0
    fun mix(channel: Int) = Math.round(channel * a + 255 * (1 - a)).toInt().coerceIn(0, 255)
    return Color(mix(c.red), mix(c.green), mix(c.blue))
}

/** Synthesize an ARGB-typed square image filled with [color]. */
internal fun solidColorImage(size: Int, color: Color): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.color = color
        g.fillRect(0, 0, size, size)
    } finally {
        g.dispose()
    }
    return img
}
