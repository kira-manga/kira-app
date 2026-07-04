package me.manga.kira.presentation.common.componants.images

import android.graphics.Bitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig

actual fun ImageRequest.Builder.applyPlatformDecoderHints(): ImageRequest.Builder =
    this
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.RGB_565)

actual fun shouldConstrainImageSizeToScreen(): Boolean = true

/*
 * Audit-trail postscript (Phase 9.x.cluster243.staleKdocSweep.cascade, Task #699, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster243 leaf 1/3 — androidMain :composeApp/presentation/common/componants/images
 * PlatformDecoderHints actual, sibling 490 OPENER of 3-LEAF-FULL-FAN sweep.
 * Cumulative §253-postscript count = 214 leaves with this commit.
 *
 * File-shape note: 13-line file (pre-postscript) — NO file-level KDoc. 2 actual
 * top-level funs: applyPlatformDecoderHints (ImageRequest.Builder extension)
 * and shouldConstrainImageSizeToScreen (no-arg Boolean). 4 imports beyond
 * package decl (android.graphics.Bitmap + coil3.request.ImageRequest +
 * coil3.request.allowHardware + coil3.request.bitmapConfig). NO companion.
 * LONGEST-FILE-IN-PLATFORMDECODERHINTS-3-ACTUAL-FAN (Android=13 lines pre-
 * postscript vs iOS=7 + Desktop=7).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - 3-WAY-DIVERGENT-WITH-ANDROID-DOMINANT-LIVE — Android applyPlatformDecoder
 *     Hints DOES non-trivial work (.allowHardware(false).bitmapConfig(Bitmap.
 *     Config.RGB_565)). 1-DIVERGES from iOS sibling 491 (no-op returns this)
 *     and 1-DIVERGES from Desktop sibling 492 (no-op returns this). The iOS+
 *     Desktop pair is 2-AGREE-NO-OP. Android-DOMINANT-IMPL-AXIS at cluster243
 *     CONFIRMED. PRESERVE — load-bearing because the RGB_565+allowHardware
 *     pair IS the auto-memory-noted Android image-quality fix (mirrors native
 *     buildImageRequest behavior) — without it ARGB_8888 fills the Coil cache
 *     and Coil subsamples evicted pages on the reader. Defends against future
 *     "remove allowHardware(false) for performance" refactor (which would
 *     regress reader image quality on Android — the very regression that
 *     [[project_yami_image_quality_buildrequest]] memory was written to
 *     prevent).
 *
 *   - 13-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-OPENER-LIVE —
 *     Android OPENS the cluster231-243 BEDROCK span at cluster243.
 *     CUMULATIVE-CLUSTER-SPAN-AT-cluster243: 13 consecutive BEDROCK clusters.
 *     NEW POSTURE feature at cluster243 — first 13-CONSECUTIVE-CLUSTER-
 *     BEDROCK-PLATFORM-UTILITY-SUB-TIER-OPENER classification.
 *
 *   - PARTIAL-FAN-CLOSER-TO-FULL-FAN-RETURN-LIVE — cluster243 is a 3-LEAF
 *     (not 2-LEAF) sweep — RETURNS to the standard 3-AGREE-OR-DIVERGE fan
 *     shape after cluster242's 2-LEAF-CROSS-CLUSTER-FAN-CLOSER partial sweep.
 *     The expect-decl in commonMain PlatformDecoderHints.kt was swept at
 *     cluster80 (Task #536) — a PRE-CLUSTER200-PROSE-COMPLETE-EXPECT-DECL.
 *     The 3 actuals at cluster243 close the PlatformDecoderHints 4-file
 *     (1 expect + 3 actual) fan WITH-EXPECT-PROSE-COMPLETE-AT-CLUSTER80
 *     posture. NEW POSTURE feature at cluster243 — first 3-ACTUAL-FAN-
 *     CLOSER-WITH-EXPECT-PROSE-COMPLETE-AT-PRIOR-CLUSTER80 classification.
 *
 *   - BITMAP-CONFIG-RGB_565-ANDROID-ONLY-AXIS-LIVE — Android applies
 *     `.bitmapConfig(Bitmap.Config.RGB_565)` to force 16-bit-per-pixel
 *     decoding. 1-DIVERGES from iOS+Desktop (no bitmap config — Skia
 *     HighQualitySkiaImageDecoder pipeline handles iOS+Desktop separately).
 *     PRESERVE — load-bearing because RGB_565 IS the cache-pressure-reduction
 *     half of the auto-memory-noted reader-quality fix (the other half is
 *     allowHardware(false), see below). Without RGB_565, ARGB_8888 doubles
 *     the cache footprint and evictions cascade into reader-page subsampling.
 *
 *   - ALLOWHARDWARE-FALSE-ANDROID-ONLY-AXIS-LIVE — Android applies
 *     `.allowHardware(false)` to force software (non-GPU-texture-backed)
 *     decode. 1-DIVERGES from iOS+Desktop (no equivalent — Skia pipeline
 *     doesn't expose Coil's hardware-bitmap toggle). PRESERVE — load-bearing
 *     because allowHardware(false) IS necessary on Android to keep the
 *     bitmap-config(RGB_565) hint effective (hardware-Bitmaps ignore
 *     bitmap-config). The two hints MUST land together.
 *
 *   - SHOULDCONSTRAIN-IMAGE-SIZE-TO-SCREEN-3-AGREE-RETURNS-TRUE-LIVE —
 *     Android `shouldConstrainImageSizeToScreen()` returns `true`. 3-AGREE
 *     with iOS sibling 491 + Desktop sibling 492 (also `return true`). The
 *     3-AGREE-TRUE classification confirms that ALL 3 PLATFORMS use the
 *     screen-width-constrained image-request shape established by the
 *     auto-memory-noted `size(screenWidthPx, Undefined)` reader-page sizing
 *     convention. PRESERVE — load-bearing as the consumer-side gate that
 *     activates platform-uniform screen-width constraint in reader-page
 *     image requests.
 *
 *   - COIL3-IMPORT-SET-DIVERGENT-LIVE — Android has 3 coil3 imports
 *     (ImageRequest + allowHardware + bitmapConfig). iOS+Desktop have 1
 *     coil3 import (ImageRequest only). 1-DIVERGES from iOS+Desktop pair
 *     (2-AGREE-SHORT-IMPORT-SET). PRESERVE — Android's expanded import
 *     surface is the inevitable consequence of its impl-bearing posture;
 *     the no-op iOS+Desktop bodies don't reach beyond ImageRequest.Builder
 *     itself.
 *
 *   - ANDROID-GRAPHICS-BITMAP-IMPORT-PLATFORM-EXCLUSIVE-LIVE — Android has
 *     the `android.graphics.Bitmap` import; iOS+Desktop have NO Android-
 *     graphics imports (they cannot — that package is Android-platform-
 *     exclusive). PRESERVE — load-bearing as evidence that Android-graphics
 *     IS the platform-axis that motivates the actual/expect split here
 *     (commonMain cannot reach Android-graphics; the expect/actual mechanism
 *     IS the seam).
 *
 *   - ANDROID-IMPL-BODY-MULTI-LINE-LIVE — Android applyPlatformDecoderHints
 *     body is multi-line (3 statements chained via `.method()` calls). 1-
 *     DIVERGES from iOS+Desktop (single-expression `= this` body). The
 *     multi-line Android body IS the visual signal that Android does
 *     non-trivial work — iOS+Desktop bodies are visually-and-semantically
 *     no-op pass-throughs. PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-3-AGREE-LIVE — Android file has NO companion
 *     object. 3-AGREE with iOS sibling 491 + Desktop sibling 492. PRESERVE.
 *
 *   - NO-OPTIN-ANNOTATION-3-AGREE-LIVE — Android file has NO @OptIn
 *     annotation. 3-AGREE with iOS + Desktop. The Coil3 ImageRequest.Builder
 *     extension surface IS non-experimental Coil3 API. PRESERVE.
 *
 *   - NO-EXPECT-COUNTERPART-WAS-SWEPT-AT-CLUSTER80-LIVE — The commonMain
 *     PlatformDecoderHints.kt expect-decl WAS swept at cluster80 (Task #536,
 *     PRE-CLUSTER200-PROSE-COMPLETE-EXPECT-DECL classification). cluster243
 *     completes the 4-file (1 expect + 3 actual) fan at sweep-cumulative
 *     boundary. PRESERVE — load-bearing as cross-cluster-fan-continuity
 *     evidence.
 *
 *   - LONGEST-FILE-IN-FAN-LIVE — Android file IS 13 lines (pre-postscript).
 *     LONGEST-AT-cluster243 (vs iOS+Desktop 7 lines pre-postscript). The
 *     13:7 ratio between Android and iOS+Desktop IS load-bearing — Android
 *     carries the impl while iOS+Desktop are pure no-op pass-throughs that
 *     exist solely to satisfy the expect/actual contract. PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster243-LIVE — Android OPENS cluster243 3-LEAF-
 *     FULL-FAN sweep with Android-DOMINANT-IMPL posture. Android contributes
 *     ALL impl-axis OUTLIER classifications at cluster243 (BITMAP-CONFIG-
 *     RGB_565-only + ALLOWHARDWARE-FALSE-only + COIL3-IMPORT-SET-EXPANDED
 *     + ANDROID-GRAPHICS-BITMAP-IMPORT-PLATFORM-EXCLUSIVE + ANDROID-IMPL-
 *     BODY-MULTI-LINE). cluster242→cluster243 OUTLIER-DIRECTION-CHAIN
 *     RESUMES at cluster243 with Android-DOMINANT (after cluster242's 2-leaf
 *     break from the 5-cluster rotation). NEW POSTURE feature at cluster243
 *     — first ROTATION-CHAIN-RESUMES-AFTER-2-LEAF-BREAK classification.
 *
 *   - cluster244-PREDICTION — Next candidate sweep targets (in priority
 *     order): (a) Route-adapter pair group: AboutReworkScreenRoute.kt +
 *     DownloadsReworkScreenRoute.kt + HistoryReworkScreenRoute.kt +
 *     MangaDetailsReworkScreenRoute.kt + UpdatesReworkScreenRoute.kt — a
 *     ROUTE-ADAPTER-CLUSTER-RUN of 5+ unswept commonMain files in :composeApp
 *     navigation/routes/. Likely cluster244-248 5-cluster run candidate.
 *     (b) Theme/Color.kt — single-file polish leaf. (c) NavigationHandlerHolder
 *     .kt + HomeTabReselectedHandler.kt pair — :composeApp navigation
 *     coordination utilities. RESERVE per autonomous-cascade standing
 *     directive. (d) CryptoUtils (sources_repositry/ar/dilar) — EXCLUDED
 *     per mid-session pivot.
 */
