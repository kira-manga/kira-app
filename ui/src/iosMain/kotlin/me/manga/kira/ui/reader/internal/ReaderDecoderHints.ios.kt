package me.manga.kira.ui.reader.internal

import coil3.request.ImageRequest

/**
 * iOS actual: no-op. Skiko has no `Bitmap.Config` analog; reader-page decode quality on iOS comes
 * from the `HighQualitySkiaImageDecoder` registered on the singleton ImageLoader in `:composeApp/App.kt`
 * (Catmull-Rom resampling), and the per-request `.maxBitmapSize(Undefined)` at the call site keeps the
 * natural strip dimensions (the documented anti-blur fix). The iOS-only webtoon-scroll stutter is
 * addressed by the native iOS reader (see `IOS_NATIVE_READER.md` / migration plan), NOT by capping the
 * decode here — a Phase-0 decode cap was trialled as a diagnostic and reverted.
 *
 * See [applyReaderDecoderHints] KDoc in `:ui/commonMain` for the cross-platform contract.
 */
internal actual fun ImageRequest.Builder.applyReaderDecoderHints(): ImageRequest.Builder = this
