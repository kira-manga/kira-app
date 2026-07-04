package me.manga.kira.ui.reader.internal

import android.graphics.Bitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig

/**
 * Android actual: applies `.allowHardware(false)` + `.bitmapConfig(Bitmap.Config.RGB_565)` per the
 * documented load-bearing parity rule. See [applyReaderDecoderHints] KDoc in `:ui/commonMain`
 * for the rationale (RGB_565 halves cache pressure → no mid-scroll evict-and-re-decode-at-
 * sample>1 → no visible quality regression).
 */
internal actual fun ImageRequest.Builder.applyReaderDecoderHints(): ImageRequest.Builder =
    this
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.RGB_565)

/*
 * §253 audit-trail postscript — cluster278 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Android platform actual of a Phase 5.x / 7.x
 * expect-fun fan; LIVE-NOT-STALE).
 *
 * LIVE evidence:
 *   - Consumer: ReaderScreen.kt:1116 chains `.applyReaderDecoderHints()` on the
 *     per-page ImageRequest.Builder inside the rework Reader page composable;
 *     import at ReaderScreen.kt:80.
 *   - Expect declaration: ReaderDecoderHints.kt:136 (`:ui/commonMain`,
 *     `internal expect fun ImageRequest.Builder.applyReaderDecoderHints()`),
 *     already swept at cluster99 (Task #555). This Android actual satisfies that
 *     expect on the androidMain target.
 *   - DI binding mechanism: NONE — this is an extension function on Coil's
 *     ImageRequest.Builder, not a Koin-bound type. Resolution is by Kotlin
 *     expect/actual link at compile time, not by Koin lookup. There is no
 *     module-level `single`/`factory` for it; LIVE-ness is proven by the
 *     ReaderScreen call site above, not by a Koin graph entry.
 *
 * Delta-axes (this Android actual vs the two no-op actuals):
 *   1. Platform API: Android-specific Coil builders `.allowHardware(false)` +
 *      `.bitmapConfig(Bitmap.Config.RGB_565)` (android.graphics.Bitmap).
 *   2. Threading/dispatcher: none — pure synchronous builder mutation; runs on
 *      whatever thread Coil invokes the request builder on; no suspension.
 *   3. Error handling: none — total function, cannot fail; returns the same
 *      builder instance for fluent chaining.
 *   4. Behavioural contract parity: the contract is "return a builder carrying
 *      reader decode hints". Android materially mutates (RGB_565 + no hardware
 *      bitmaps to halve cache pressure); Desktop and iOS satisfy the SAME
 *      contract as identity no-ops because Skiko has no Bitmap.Config analog and
 *      quality there comes from HighQualitySkiaImageDecoder. All three return a
 *      valid ImageRequest.Builder — parity holds at the type/contract level.
 *   5. Why mutate here only: documented load-bearing anti-blur fix — without
 *      RGB_565 the ARGB_8888 default fills Coil's memory cache ~2x faster,
 *      evicted pages re-decode at sample size above 1, producing visible
 *      mid-scroll blur (project image-quality memory).
 *
 * Original Phase 7.x.reader-era KDoc preserved verbatim above.
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the
 * header block above the actual fun). This appended block adds exactly one
 * opener and one closer with no interior delimiter sequences — balanced.
 */
