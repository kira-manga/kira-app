package me.manga.kira.ui.components

import kotlin.math.roundToInt

/** The placement interval of a full-sized thumb, in the same rounded pixels as its layout. */
internal data class FastScrollerGeometry private constructor(
    val minOffsetPx: Int,
    val maxOffsetPx: Int,
    val thumbHeightPx: Int,
) {
    val trackHeightPx: Float = (maxOffsetPx - minOffsetPx).toFloat()
    val viewportHeightPx: Float = (maxOffsetPx - minOffsetPx + thumbHeightPx).toFloat()

    fun clampOffset(offset: Float): Float =
        if (offset.isFinite()) {
            offset.toDouble().coerceIn(minOffsetPx.toDouble(), maxOffsetPx.toDouble()).toFloat()
        } else {
            minOffsetPx.toFloat()
        }

    fun placementOffset(offset: Float): Int = clampOffset(offset).roundToInt().coerceIn(minOffsetPx, maxOffsetPx)

    fun offsetAfterDelta(
        offset: Float,
        delta: Float,
    ): Float {
        val current = clampOffset(offset)
        if (!delta.isFinite()) return current
        // Rebase before applying the delta; a shrinking viewport must not eat the next up-drag.
        return (current.toDouble() + delta.toDouble())
            .coerceIn(minOffsetPx.toDouble(), maxOffsetPx.toDouble())
            .toFloat()
    }

    fun scrollRatio(offset: Float): Float =
        ((clampOffset(offset).toDouble() - minOffsetPx) / trackHeightPx)
            .coerceIn(0.0, 1.0)
            .toFloat()

    fun offsetForScroll(proportion: Float): Float =
        if (proportion.isFinite()) {
            clampOffset(minOffsetPx + trackHeightPx * proportion.coerceIn(0f, 1f))
        } else {
            minOffsetPx.toFloat()
        }

    companion object {
        fun create(
            contentHeightPx: Int,
            topPaddingPx: Float,
            bottomPaddingPx: Float,
            afterContentPaddingPx: Int,
            thumbHeightPx: Float,
        ): FastScrollerGeometry? =
            when {
                contentHeightPx < 0 || afterContentPaddingPx < 0 -> null
                !topPaddingPx.isValidPadding() || !bottomPaddingPx.isValidPadding() -> null
                !thumbHeightPx.isFinite() || thumbHeightPx <= 0f -> null
                else -> {
                    val top = topPaddingPx.roundToInt()
                    val bottom = bottomPaddingPx.roundToInt()
                    val thumb = thumbHeightPx.roundToInt()
                    // Large padding must not wrap into a seemingly positive track.
                    val end = contentHeightPx.toLong() - bottom - afterContentPaddingPx - thumb
                    if (thumb > 0 && end > top) FastScrollerGeometry(top, end.toInt(), thumb) else null
                }
            }

        private fun Float.isValidPadding(): Boolean = isFinite() && this >= 0f
    }
}
