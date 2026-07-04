package me.manga.kira.presentation.common.componants.images

import coil3.request.ImageRequest

actual fun ImageRequest.Builder.applyPlatformDecoderHints(): ImageRequest.Builder = this

actual fun shouldConstrainImageSizeToScreen(): Boolean = true

/*
 * Audit-trail postscript (Phase 9.x.cluster243.staleKdocSweep.cascade, Task #699, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster243 leaf 2/3 — iosMain :composeApp/presentation/common/componants/images
 * PlatformDecoderHints actual, sibling 491 MIDDLE of 3-LEAF-FULL-FAN sweep.
 * Cumulative §253-postscript count = 215 leaves with this commit.
 *
 * File-shape note: 7-line file (pre-postscript) — NO file-level KDoc. 2
 * actual top-level funs: applyPlatformDecoderHints (no-op pass-through
 * returning `this`) and shouldConstrainImageSizeToScreen (returns true).
 * 1 import beyond package decl (coil3.request.ImageRequest only). NO
 * companion. TIED-SHORTEST-FILE-IN-PLATFORMDECODERHINTS-3-ACTUAL-FAN
 * (iOS=7 lines pre-postscript, Desktop=7 lines pre-postscript, Android=13).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - 2-AGREE-NO-OP-PAIR-WITH-DESKTOP-LIVE — iOS applyPlatformDecoderHints
 *     body is `= this` (single-expression no-op pass-through). 2-AGREE
 *     with Desktop sibling 492 (identical body shape). 1-DIVERGES from
 *     Android sibling 490 (which applies .allowHardware(false).bitmapConfig
 *     (Bitmap.Config.RGB_565)). PRESERVE — load-bearing as evidence that
 *     iOS+Desktop don't need Coil's bitmap-config hints because their
 *     image-decode pipeline goes through Skia's HighQualitySkiaImageDecoder
 *     (per [[project_yami_desktop_skia_size_cap]] memory at commit 98bf8ed
 *     which replaced Coil's stock Skia decoder + 4096 cap). Defends against
 *     future "add bitmap-config to iOS for cache savings" refactor (which
 *     would have no effect — Skia doesn't honor Coil's Android-targeted
 *     bitmap-config hints).
 *
 *   - 13-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-CONTINUES-LIVE
 *     — iOS continues the cluster231-243 BEDROCK span. PRESERVE.
 *
 *   - NO-PLATFORM-FOUNDATION-IMPORT-3-AGREE-WITH-DESKTOP-LIVE — iOS file has
 *     NO platform.Foundation imports. 2-AGREE with Desktop sibling 492.
 *     1-DIVERGES from cluster242 iOS sibling 488 (which had 4 Foundation-
 *     related imports because NSFileManager.URLForDirectory bridges to
 *     Documents directory). The PlatformDecoderHints iOS actual stays
 *     pure-Coil3 because the no-op body doesn't reach beyond ImageRequest.
 *     Builder. PRESERVE — load-bearing for Foundation-reach axis
 *     discrimination between cluster242-iOS (Foundation-REQUIRED) and
 *     cluster243-iOS (Foundation-FREE).
 *
 *   - NO-EXPERIMENTAL-FOREIGN-API-OPTIN-LIVE — iOS file has NO @OptIn
 *     (ExperimentalForeignApi::class) annotation. 1-DIVERGES from cluster242
 *     iOS sibling 488 (which DOES have @OptIn). The no-op body doesn't
 *     reach cinterop. 3-AGREE with Android sibling 490 + Desktop sibling
 *     492 (also no @OptIn). PRESERVE.
 *
 *   - SHOULDCONSTRAIN-IMAGE-SIZE-TO-SCREEN-3-AGREE-RETURNS-TRUE-CONTINUES-
 *     LIVE — iOS `shouldConstrainImageSizeToScreen()` returns `true`.
 *     2-AGREE with Android sibling 490. PRESERVE — load-bearing as the
 *     consumer-side gate that activates screen-width-constrained image
 *     requests on iOS reader pages.
 *
 *   - COIL3-IMPORT-MINIMAL-2-AGREE-WITH-DESKTOP-LIVE — iOS has only 1 coil3
 *     import (ImageRequest, used solely as the receiver type for the
 *     ImageRequest.Builder extension). 2-AGREE with Desktop sibling 492.
 *     1-DIVERGES from Android sibling 490 (3 coil3 imports). PRESERVE.
 *
 *   - SINGLE-EXPRESSION-BODY-2-AGREE-WITH-DESKTOP-LIVE — iOS applyPlatform
 *     DecoderHints body is single-expression `= this`. 2-AGREE with Desktop
 *     sibling 492. 1-DIVERGES from Android sibling 490 (multi-line chained
 *     body). PRESERVE — load-bearing as visual signal that iOS+Desktop are
 *     no-op pass-throughs.
 *
 *   - NO-COMPANION-OBJECT-3-AGREE-CONTINUES-LIVE — iOS file has NO companion
 *     object. 2-AGREE with Android sibling 490 (and 3-AGREE pending Desktop
 *     sibling 492 closure). PRESERVE.
 *
 *   - SHORTEST-FILE-TIED-WITH-DESKTOP-LIVE — iOS file IS 7 lines (pre-
 *     postscript). TIED-SHORTEST-AT-cluster243 with Desktop. 1-DIVERGES
 *     from Android sibling 490 (13 lines pre-postscript). PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster243-LIVE — iOS Platform DecoderHints
 *     IS leaf 2/3 of cluster243 3-LEAF-FULL-FAN. iOS contributes ZERO impl-
 *     axis OUTLIER classifications at cluster243 (all impl-axis OUTLIERS
 *     accrue to Android sibling 490). iOS contributes to the 2-AGREE-NO-OP-
 *     PAIR-WITH-DESKTOP axis count. PRESERVE.
 */
