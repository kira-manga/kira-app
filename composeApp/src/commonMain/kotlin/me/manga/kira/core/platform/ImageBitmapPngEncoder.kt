package me.manga.kira.core.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encode a Compose [ImageBitmap] to PNG bytes (Reader parity item #5 — share current page).
 *
 * Lives in `:composeApp` (not `:ui`, not `:platform`) because it is the bridge that lets the
 * Reader route adapter turn a Compose-captured page bitmap into the `ByteArray` the existing
 * `:platform` [me.manga.kira.platform.image.ScreenshotProvider.shareBitmapBytes] SPI consumes.
 * `:ui` stays multiplatform-pure (it only ever hands back an `ImageBitmap`); `:platform` already
 * owns the share-sheet plumbing and deliberately takes already-encoded bytes (see ScreenshotProvider
 * KDoc — "callers pass already-encoded image bytes"). This thin encoder is the one piece neither
 * layer can own without crossing a boundary, so it sits at the composition root next to the route
 * adapter that calls it.
 *
 * Per-platform actuals:
 *  - Android: `ImageBitmap.asAndroidBitmap().compress(PNG, 100, ...)` — same PNG path the legacy
 *    `ScreenshotUtils.captureAndShare` used (`Bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)`).
 *  - iOS / Desktop (Skiko): `Image.makeFromBitmap(asSkiaBitmap()).encodeToData(PNG)`.
 *
 * Returns `null` on an encode failure so the caller can no-op silently rather than crash a share.
 */
expect fun encodeImageBitmapToPng(bitmap: ImageBitmap): ByteArray?
