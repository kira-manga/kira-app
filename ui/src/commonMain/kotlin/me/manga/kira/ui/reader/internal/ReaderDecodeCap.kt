package me.manga.kira.ui.reader.internal

/**
 * Decode-width budget for reader pages (mobile hardening 2026-07-04, audit "unbounded decode"
 * P2). The reader deliberately lifts Coil's 4096×4096 `maxBitmapSize` so tall webtoon strips
 * don't collapse to ~234px width (see the `ReaderScreen` request-builder comment) — but with NO
 * cap at all, `FillWidth` (Scale.FILL) makes Coil's sample-size math return 1 for an
 * Undefined-height constraint, so an 800×30000 strip decodes at full natural size (~48MB in
 * RGB_565, ~96MB where the 565 hint doesn't apply) as a SOFTWARE bitmap — an OOM vector on
 * low-RAM devices with several strips co-visible.
 *
 * The fix caps the decode WIDTH only (height stays Undefined — Coil applies the two `maxBitmapSize`
 * axes independently, verified against the 3.5.0 sources, so no aspect-driven width collapse is
 * reintroduced) at window-width × [READER_DECODE_WIDTH_HEADROOM]. Anything decoded wider than the
 * headroom cannot be displayed sharper: the layout renders at window width, and pinch-zoom tops out
 * near the headroom factor before Compose upscales anyway. Typical strips (≤ window width) are
 * entirely unaffected. Coil applies the cap with exact bilinear scaling (inDensity /
 * inTargetDensity), not blocky power-of-2 sampling.
 *
 * [READER_DECODE_WIDTH_HEADROOM] is the zoom headroom kept above the window width so pinch-zoom
 * stays sharp — mirrors the iOS native reader's `ReaderPagedCell.zoomDecodeFactor` (2.5).
 */
internal const val READER_DECODE_WIDTH_HEADROOM: Float = 2.5f

/**
 * The decode-width cap in pixels for the given window width, or null when the window size is not
 * yet known (first-frame `containerSize == 0`) — callers fall back to an unbounded request then,
 * which is the pre-cap behavior for at most one composition frame.
 */
internal fun readerDecodeMaxWidthPx(windowWidthPx: Int): Int? =
    windowWidthPx.takeIf { it > 0 }?.let { (it * READER_DECODE_WIDTH_HEADROOM).toInt() }
