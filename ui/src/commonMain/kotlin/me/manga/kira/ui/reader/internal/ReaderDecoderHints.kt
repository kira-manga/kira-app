package me.manga.kira.ui.reader.internal

import coil3.request.ImageRequest

/**
 * Platform-specific Coil [ImageRequest.Builder] tweaks for reader-page decode, mirroring the legacy
 * `:composeApp/.../presentation/common/componants/images/PlatformDecoderHints.kt`
 * `applyPlatformDecoderHints` extension. Lifted into `:ui` so the rework Reader's inline
 * [ImageRequest] construction reaches parity with the legacy reader's
 * `rememberSourceImageRequest` builder without the rework reaching back into `:composeApp`.
 *
 * Phase 7.x.reader.modelayout.pageprogress Step 7 — closes the open item documented in
 * [me.manga.kira.ui.reader.ReaderScreen]'s class-level KDoc:
 *
 *  > **Per-page Android RGB_565 + allowHardware(false) decoder hints.** Those live today in
 *  > `composeApp`'s `applyPlatformDecoderHints` expect/actual (used by the legacy reader). The
 *  > shell uses inline Coil `ImageRequest` construction with headers + per-request
 *  > `maxBitmapSize(Undefined, Undefined)` (the more critical anti-blur fix). A follow-up
 *  > `:ui/.../reader/internal/` micro-slice lifts the platform hints into `:ui` via
 *  > expect/actual without changing this composable's contract.
 *
 * **On Android**: applies `.allowHardware(false)` + `.bitmapConfig(Bitmap.Config.RGB_565)`. The
 * Android native app sets these per-page in `BaseManga.buildImageRequest` for a deliberate
 * reason — manga pages are predominantly low-color; RGB_565 halves the bitmap memory footprint
 * vs the default ARGB_8888, which keeps roughly twice as many pages resident in Coil's memory
 * cache. Without it, the cache fills faster, Coil evicts and re-decodes pages mid-scroll, often
 * at sample sizes >1 which produces visibly blurry pages. **This is the load-bearing fix for the
 * "image quality so bad I can't read it" regression documented in the project's image-quality
 * memory.**
 *
 * `.allowHardware(false)` forces software bitmaps so any subsequent canvas-read / pool-reuse
 * paths (e.g. the zoomable modifier's bitmap probes) don't trip on hardware-bitmap
 * restrictions on older API levels.
 *
 * **On iOS / Desktop**: no-op — Skiko handles decode through its own pixel-format pipeline
 * (`HighQualitySkiaImageDecoder` registered in `:composeApp` provides the Catmull-Rom
 * resampling for those targets; pixel-format selection is implicit). The actuals return the
 * builder unchanged.
 *
 * **DIP**: the expect lives in `:ui/commonMain` and depends only on `coil-compose` (transitive
 * `coil-core`). The Android actual reaches into the platform `android.graphics.Bitmap.Config`
 * type — same scope as any `:ui/androidMain` actual. No `:platform` / `:data` reach-down.
 *
 * **SRP**: one rule — "apply per-platform decoder pixel-format hints to a reader page
 * ImageRequest". Pairs with the existing inline `.maxBitmapSize(Undefined, Undefined)` which
 * remains in [me.manga.kira.ui.reader.ReaderScreen] (different concern: bitmap-size cap, not
 * pixel-format).
 *
 * **Reader-scoped (not general purpose)**: lives under `reader/internal/` because manga-page
 * decode-pattern assumptions (low-color → RGB_565 acceptable) don't generalize to library /
 * details covers, which keep using the singleton ImageLoader's default ARGB_8888. The legacy
 * `applyPlatformDecoderHints` ALSO ships in `:composeApp` and serves both the legacy reader and
 * the legacy library/details via `rememberSourceImageRequest`; the rework's covers don't yet
 * use a builder-level helper (they use `AsyncImage(model = url)` directly through the singleton
 * loader), so reader-scoping the new helper is the minimum-blast-radius change.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster99.staleKdocSweep.cascade,
 * Task #555, 2026-05-28): the multi-claim Phase 7.x.reader.modelayout.
 * pageprogress Step 7 expect-fun manifest above is classified as
 * follows after recursive symbol verification across the KMP graph
 * (fortieth sibling of the cluster57-98 sweep — sibling of cluster98
 * ComplaintIcons; closes the wave-6 batch):
 *  (a) "mirroring the legacy `:composeApp/.../presentation/common/
 *  componants/images/PlatformDecoderHints.kt` `applyPlatformDecoder-
 *  Hints` extension. Lifted into `:ui` so the rework Reader's inline
 *  ImageRequest construction reaches parity ... without the rework
 *  reaching back into `:composeApp`" — LIVE-NOT-STALE. The legacy
 *  `applyPlatformDecoderHints` LIVE at `composeApp/src/commonMain/
 *  kotlin/me/manga/yamiapk/presentation/common/componants/images/
 *  PlatformDecoderHints.kt` (which received its own §253 postscript
 *  at cluster80, Task #536). The rework `applyReaderDecoderHints`
 *  expect at L57 of THIS file lives in `:ui/commonMain` — no
 *  reach-back from `:ui` into `:composeApp` occurs.
 *  (b) "Phase 7.x.reader.modelayout.pageprogress Step 7 — closes
 *  the open item documented in [ReaderScreen]'s class-level KDoc"
 *  with the embedded `>` blockquote — LIVE-NOT-STALE. ReaderScreen.
 *  kt KDoc-quoted text ("Per-page Android RGB_565 plus allow-
 *  Hardware(false) decoder hints ... A follow-up :ui/.../reader/
 *  internal/ micro-slice lifts the platform hints into :ui via
 *  expect/actual without changing this composable's contract") was
 *  the documented open item; THIS file at the documented path
 *  `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/reader/internal/
 *  ReaderDecoderHints.kt` is the fulfillment.
 *  (c) "On Android: applies `.allowHardware(false)` plus
 *  `.bitmapConfig(Bitmap.Config.RGB_565)`" — FULFILLED-PREDICTION.
 *  The Android actual at `ui/src/androidMain/kotlin/me/manga/yamiapk/
 *  ui/reader/internal/ReaderDecoderHints.android.kt:14-17` LIVE
 *  implements `this.allowHardware(false).bitmapConfig(Bitmap.Config.
 *  RGB_565)` exactly as the commonMain KDoc forecasts.
 *  (d) "On iOS / Desktop: no-op ... HighQualitySkiaImageDecoder
 *  registered in `:composeApp` provides the Catmull-Rom resampling"
 *  — FULFILLED-PREDICTION. The iOS actual at `ui/src/iosMain/...
 *  ReaderDecoderHints.ios.kt:13` LIVE returns the receiver unchanged
 *  (`= this`). The Desktop sibling at `ui/src/desktopMain/...
 *  ReaderDecoderHints.desktop.kt` follows the same no-op pattern.
 *  HighQualitySkiaImageDecoder LIVE at `platform/src/nonAndroidMain/
 *  kotlin/me/manga/yamiapk/platform/image/HighQualitySkiaImageDecoder.
 *  kt` (registered on the singleton ImageLoader; deferred quality
 *  fix lives there per the project's image-quality memory).
 *  (e) "load-bearing fix for the 'image quality so bad I can't read
 *  it' regression documented in the project's image-quality memory"
 *  — LIVE-NOT-STALE. Memory citation aligns with the durable
 *  `project_yami_image_quality_buildrequest.md` MEMORY.md entry that
 *  documents the Android RGB_565 plus allowHardware(false) plus
 *  per-request `maxBitmapSize` pattern. The load-bearing claim
 *  preserves the post-port investigation outcome.
 *  (f) "DIP: the expect lives in `:ui/commonMain` and depends only
 *  on `coil-compose` (transitive `coil-core`). The Android actual
 *  reaches into the platform `android.graphics.Bitmap.Config` type
 *  — same scope as any :ui/androidMain actual. No :platform / :data
 *  reach-down" — LIVE-NOT-STALE. THIS file's imports at L3 contain
 *  only `coil3.request.ImageRequest`. The Android actual L3-6
 *  imports limited to `android.graphics.Bitmap` plus `coil3.request.
 *  {ImageRequest, allowHardware, bitmapConfig}`. No `:platform` or
 *  `:data` reach. DIP holds.
 *  (g) "SRP: one rule — 'apply per-platform decoder pixel-format
 *  hints to a reader page ImageRequest'. Pairs with the existing
 *  inline `.maxBitmapSize(Undefined, Undefined)` which remains in
 *  [ReaderScreen] (different concern: bitmap-size cap, not pixel-
 *  format)" — LIVE-NOT-STALE. The Android actual at L14-17 applies
 *  exactly two builder transformations, both pixel-format-related.
 *  The bitmap-size cap concern stays in ReaderScreen as documented.
 *  (h) "Reader-scoped (not general purpose) ... the rework's covers
 *  don't yet use a builder-level helper (they use AsyncImage(model
 *  = url) directly through the singleton loader)" — LIVE-NOT-STALE.
 *  Recursive Grep for `applyReaderDecoderHints` matches ONLY ReaderScreen.
 *  kt as a consumer; rework Library / Details / History / Updates /
 *  Statistics / WhatsNew cover sites use `AsyncImage(model = url)`
 *  directly. Minimum-blast-radius scope holds.
 *  Two FULFILLED-PREDICTION classifications plus six LIVE-NOT-STALE
 *  classifications STAND on their own merits as a faithful reader-
 *  decoder-hints expect-fun manifest. Original Phase 7.x.reader-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
internal expect fun ImageRequest.Builder.applyReaderDecoderHints(): ImageRequest.Builder
