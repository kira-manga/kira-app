package me.manga.kira.ui.reader.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mobile hardening 2026-07-04 — the reader's decode-width budget: window width × zoom headroom,
 * and NO cap while the window size is unknown (first-frame `containerSize == 0` falls back to the
 * pre-cap unbounded request rather than decoding everything at width 0).
 */
class ReaderDecodeCapTest {
    @Test
    fun capIsWindowWidthTimesZoomHeadroom() {
        assertEquals(
            (PHONE_PORTRAIT_WIDTH_PX * READER_DECODE_WIDTH_HEADROOM).toInt(),
            readerDecodeMaxWidthPx(PHONE_PORTRAIT_WIDTH_PX),
        )
        assertEquals(
            (PHONE_PRO_WIDTH_PX * READER_DECODE_WIDTH_HEADROOM).toInt(),
            readerDecodeMaxWidthPx(PHONE_PRO_WIDTH_PX),
        )
    }

    @Test
    fun unknownWindowSizeYieldsNoCap() {
        assertNull(readerDecodeMaxWidthPx(0))
        assertNull(readerDecodeMaxWidthPx(-1))
    }

    private companion object {
        const val PHONE_PORTRAIT_WIDTH_PX = 1080
        const val PHONE_PRO_WIDTH_PX = 1170
    }
}
