package me.manga.kira.platform.cbz

/**
 * iOS-only switch selecting the CBZ page encoder, with the **native libwebp encoder as the default** and
 * the skiko-based [SkiaWebpEncoder] retained as a fallback/benchmark path.
 *
 * Default **ON** (`true`): iOS ships [IosLibWebpEncoder] (ImageIO/CoreGraphics decode + libwebp encode),
 * verified on-device. It keeps the big decoded webtoon-strip pixel buffer in CoreGraphics-native memory,
 * which eliminates the Kotlin/Native stop-the-world GC stalls (~½ s) that froze the UI during the
 * COMPRESSING stage on the Skia path. Output format, banding, and the verbatim-on-`null` contract are
 * identical, so the swap is transparent to the reader and the CBZ format.
 *
 * **Output stays WebP** — never HEIC — for cross-platform/sharing parity with Android (a chapter
 * downloaded on iOS must open and share correctly on Android and elsewhere).
 *
 * Set to `false` to fall back to [SkiaWebpEncoder] (e.g. to A/B the two on the same chapter — both emit
 * the same `DLPERF.webpEncode` line tagged `enc=libwebp` vs `enc=skia` when `BgDownloadLog.DLPERF` is on).
 * Mirrors the `IosReaderFlags` / `DownloadEngineFlags` rollback pattern. **Android uses
 * `Bitmap.compress`; Desktop always uses skiko — neither consults this flag.**
 */
internal object IosWebpEncoderFlags {
    const val USE_LIBWEBP: Boolean = true
}
