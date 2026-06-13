package io.github.yuroyami.kmpssot

import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/** Run [block] on a quality-configured Graphics2D, disposing it afterward; returns the receiver. */
internal inline fun BufferedImage.withGraphics(block: Graphics2D.() -> Unit): BufferedImage {
    val g = createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.composite = AlphaComposite.SrcOver
        g.block()
    } finally {
        g.dispose()
    }
    return this
}

internal fun newArgb(size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

internal fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
    if (src.width == w && src.height == h) return src
    return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).withGraphics { drawImage(src, 0, 0, w, h, null) }
}

/** Draw [img] scaled to fit *inside* the box, preserving aspect ratio, centred. Never stretches. */
internal fun Graphics2D.drawContain(img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
    val scale = minOf(w.toDouble() / img.width, h.toDouble() / img.height)
    val dw = (img.width * scale).toInt().coerceAtLeast(1)
    val dh = (img.height * scale).toInt().coerceAtLeast(1)
    drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null)
}

/** Draw [img] scaled to *cover* the box, preserving aspect ratio, centred; overflow is clipped. */
internal fun Graphics2D.drawCover(img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
    val scale = maxOf(w.toDouble() / img.width, h.toDouble() / img.height)
    val dw = (img.width * scale).toInt().coerceAtLeast(1)
    val dh = (img.height * scale).toInt().coerceAtLeast(1)
    val prevClip = clip
    clip = Rectangle(x, y, w, h)
    drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null)
    clip = prevClip
}

internal fun applyCircleMask(src: BufferedImage): BufferedImage =
    BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).withGraphics {
        clip = Ellipse2D.Float(0f, 0f, src.width.toFloat(), src.height.toFloat())
        drawImage(src, 0, 0, null)
    }
