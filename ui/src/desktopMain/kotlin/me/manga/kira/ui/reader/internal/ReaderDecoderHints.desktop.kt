package me.manga.kira.ui.reader.internal

import coil3.request.ImageRequest

/**
 * Desktop actual: no-op. Skiko has no `Bitmap.Config` analog — pixel format is implicit in the
 * Skia surface backend chosen at decode time. Quality on Desktop is supplied by the
 * `HighQualitySkiaImageDecoder` registered on the singleton ImageLoader in `:composeApp/App.kt`
 * (Catmull-Rom cubic resampling instead of Coil's stock nearest-neighbor `SkiaImageDecoder`).
 *
 * See [applyReaderDecoderHints] KDoc in `:ui/commonMain` for the cross-platform contract.
 */
internal actual fun ImageRequest.Builder.applyReaderDecoderHints(): ImageRequest.Builder = this

/*
 * §253 audit-trail postscript — cluster278 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (Desktop platform actual of a Phase 5.x / 7.x
 * expect-fun fan; LIVE-NOT-STALE).
 *
 * LIVE evidence:
 *   - Consumer: ReaderScreen.kt:1116 chains `.applyReaderDecoderHints()` on the
 *     per-page ImageRequest.Builder in the rework Reader composable; import at
 *     ReaderScreen.kt:80. The Desktop target links THIS actual to that call.
 *   - Expect declaration: ReaderDecoderHints.kt:136 (`:ui/commonMain`),
 *     already swept at cluster99 (Task #555). This desktopMain actual satisfies
 *     that expect.
 *   - DI binding mechanism: NONE — extension function on Coil's
 *     ImageRequest.Builder, resolved by Kotlin expect/actual at compile time,
 *     not bound in any Koin module. LIVE-ness is proven by the ReaderScreen
 *     call site, not by a Koin graph entry.
 *
 * Delta-axes (this Desktop actual vs Android + iOS):
 *   1. Platform API: identity no-op — returns the receiver builder unchanged.
 *      Skiko exposes no `Bitmap.Config` analog; pixel format is implicit in the
 *      Skia surface backend chosen at decode time, so there is no per-request
 *      hint to set.
 *   2. Threading/dispatcher: none — synchronous identity return; no suspension,
 *      no dispatcher hop.
 *   3. Error handling: none — total function, cannot fail.
 *   4. Where Desktop quality actually comes from: the singleton ImageLoader in
 *      `:composeApp/App.kt` registers HighQualitySkiaImageDecoder (Catmull-Rom
 *      cubic resampling) instead of Coil's stock nearest-neighbor decoder. The
 *      reader-page hint is therefore intentionally empty on this target.
 *   5. Behavioural contract parity: matches iOS exactly (both identity no-ops)
 *      and satisfies the same "return a reader-hinted builder" contract as the
 *      mutating Android actual — every actual returns a valid
 *      ImageRequest.Builder, so parity holds at the contract level.
 *
 * Original Phase 7.x.reader-era KDoc preserved verbatim above.
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the
 * header block above the actual fun). This appended block adds exactly one
 * opener and one closer with no interior delimiter sequences — balanced.
 */
