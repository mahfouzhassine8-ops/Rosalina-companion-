package com.rosalina.unified

import kotlin.math.*

/** Local 2-D portrait rig. It deforms the approved pixels, not a replacement character.
 * This is not a neural talking-head model or phoneme-accurate lip sync. */
internal class AvatarRig {
    private val xs = (0..64).map { it / 64f }
    private val ys = (0..128).map { it / 128f }
    val columns = xs.size - 1
    val rows = ys.size - 1
    private val count = xs.size * ys.size
    val vertices = FloatArray(count * 2)
    private val baseX = FloatArray(count)
    private val baseY = FloatArray(count)
    private val head = FloatArray(count)
    private val hair = FloatArray(count)
    private val shoulder = FloatArray(count)
    private val eye = FloatArray(count)
    private val jaw = FloatArray(count)

    init {
        var i = 0
        for (y in ys) for (x in xs) {
            baseX[i] = x; baseY[i] = y
            head[i] = mask(x, y, .5f, .16f, .29f, .20f)
            hair[i] = mask(x, y, .30f, .40f, .11f, .26f) + mask(x, y, .69f, .39f, .09f, .24f)
            shoulder[i] = mask(x, y, .5f, .335f, .26f, .09f)
            // Landmarks measured on the exact 180 x 261 baseline portrait.
            eye[i] = (y - .205f) * mask(x, y, .428f, .205f, .051f, .020f) +
                (y - .1895f) * mask(x, y, .559f, .1895f, .052f, .021f)
            jaw[i] = mask(x, y, .5f, .268f, .11f, .035f)
            i++
        }
    }

    private fun mask(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
        val distance = sqrt(((x - cx) / rx).pow(2) + ((y - cy) / ry).pow(2))
        val edge = ((1f - distance) / .42f).coerceIn(0f, 1f)
        return edge * edge * (3f - 2f * edge)
    }

    fun update(seconds: Float, energy: Float, listening: Boolean, thinking: Boolean,
               left: Float, top: Float, width: Float, height: Float, motion: Boolean = true) {
        val t = if (seconds.isFinite()) seconds else 0f
        val audio = if (energy.isFinite()) energy.coerceIn(0f, 1f) else 0f
        val breath = if (motion) sin(t * 1.55f) else 0f
        val angle = if (motion) .012f * sin(t * .72f) + (if (listening) -.008f else 0f) else 0f
        val nod = if (motion) .0015f * sin(t * 1.10f) + (if (thinking) .0018f else 0f) else 0f
        val sway = if (motion) .0045f * sin(t * .92f) else 0f
        val blink = if (motion) blink(t) else 0f
        val ca = cos(angle); val sa = sin(angle)
        for (i in 0 until count) {
            val x = baseX[i]; val y = baseY[i]
            // Rotation is in image-pixel aspect, around the neck, and masked to the head.
            val px = (x - .5f) * 180f; val py = (y - .30f) * 261f
            val dx = ((ca * px - sa * py - px) / 180f + sway) * head[i]
            val dy = ((sa * px + ca * py - py) / 261f + nod) * head[i]
            vertices[i * 2] = left + (x + dx + sway * .45f * hair[i]) * width
            vertices[i * 2 + 1] = top + (y + dy - .0024f * breath * shoulder[i] -
                .90f * blink * eye[i] + (if (motion) .0035f * audio * jaw[i] else 0f)) * height
        }
    }

    companion object {
        /** A quick close/open, with a less frequent second blink; deterministic for regression tests. */
        fun blink(seconds: Float): Float {
            val cycle = ((seconds % 5.4f) + 5.4f) % 5.4f
            val first = (1f - abs(cycle - 1.72f) / .14f).coerceIn(0f, 1f)
            val second = if ((seconds / 5.4f).toInt() % 3 == 2)
                (1f - abs(cycle - 2.06f) / .11f).coerceIn(0f, 1f) else 0f
            val value = max(first, second)
            return value * value * (3f - 2f * value)
        }
    }
}
