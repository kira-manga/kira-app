package me.manga.kira.presentation.features.whatsnew.data

import kotlinx.serialization.Serializable

@Serializable
data class RemoteWhatsNewFeature(
    val title: Map<String, String>,
    val description: Map<String, String>,
    val mediaType: String,
    val imageRes: String? = null,
    val imageResList: List<String> = emptyList(),
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isNew: Boolean = false,
    val version: String? = null,
)

@Serializable
data class WhatsNewResponse(
    val features: List<RemoteWhatsNewFeature>,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster203.staleKdocSweep.cascade, Task #659, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster203 leaf 4/4 — :shared/whatsnew/data/ tier midbody, sibling 366. CLUSTER203 CLOSER.
 * Cumulative §253-postscript count = 91 leaves with this commit.
 *
 * File-shape note: 21-line @Serializable data-class pair —
 *   • RemoteWhatsNewFeature (9 fields: title/description as Map<String,String>; mediaType +
 *     imageRes + imageResList + imageUrl + videoUrl + isNew + version as plain types; 7 of 9
 *     default-valued — Map fields are non-default).
 *   • WhatsNewResponse (1 field — features: List<RemoteWhatsNewFeature>).
 * 1 import (kotlinx.serialization.Serializable). No KDoc headers.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE — directly consumed by:
 *       1. WhatsNewRemoteDataSource.kt (sibling — cluster204 cohort) — deserializes
 *          `WhatsNewResponse` from the remote JSON fetcher; iterates `response.features`
 *          (List<RemoteWhatsNewFeature>) and calls `getLocalizedFeature(remote, lang)` per
 *          element to project each RemoteWhatsNewFeature → LocalizedFeature.
 *       2. WhatsNewRepositoryImpl.kt (:data — rework strangler-fig consumer) — its KDoc at L23
 *          documents "Returns `Result<WhatsNewResponse>` with structured failure handling" —
 *          the rework :data path RECEIVES the legacy `WhatsNewResponse` type as the strangler-
 *          fig contract boundary. Rework :data does NOT re-declare these DTOs.
 *
 *   • INVERTED-PARALLEL — no same-named rework counterparts at `:data/dto/` or anywhere. The
 *     rework :data layer consumes both DTO types directly (WhatsNewResponse as the bridge
 *     Result-carrier, RemoteWhatsNewFeature transitively via response.features iteration).
 *     Same strangler-fig posture as sibling 365 (LocalizedFeature) — legacy :shared is the
 *     authoritative wire-format declaration; rework :data treats it as a still-functioning
 *     fetch library.
 *
 *   • DOC-LACUNA — no KDoc headers on either class. Per §253 — preserved. The @Serializable
 *     contract + field names are self-describing as DTO shape; the consumers (WhatsNewRemote
 *     DataSource + WhatsNewRepositoryImpl) carry the prose-rich KDocs explaining the
 *     localization pipeline + Result-wrapping contract.
 *
 *   • SERIALIZATION-WIRE-FORMAT-NOT-DRIFT — @Serializable on both classes. The default-valued
 *     fields on RemoteWhatsNewFeature (imageRes/imageResList/imageUrl/videoUrl/isNew/version)
 *     are FORWARD-COMPAT for partial server responses — older entries may omit these keys.
 *     The 2 non-default Map fields (title + description) are REQUIRED — at minimum a wire entry
 *     MUST carry localizable copy. DO NOT add defaults to title/description during DTO
 *     cleanup passes — that would mask a legitimately-malformed server payload.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import (kotlinx.serialization.Serializable). Otherwise
 *     pure stdlib.
 *
 * Cross-cluster :shared/whatsnew/ subdirectory partial-sweep status (cluster203 closer):
 *
 *   • Wave-62 opens the legacy :shared/whatsnew/ subdir sweep — cluster203 covers 4 of 7
 *     files (model/ pair + data/ DTO+projection pair). Remaining 3 files (data/
 *     WhatsNewRemoteDataSource.kt + data/getDefaultFeatures.kt — wait, getDefaultFeatures is
 *     at ui/viewmodel/ path — verify in cluster204 scout — plus ui/viewmodel/WhatsNewViewModel.kt)
 *     deferred to cluster204 as the whatsnew/ subdir closer.
 *
 *   • Naming-axis patterns across cluster203:
 *       - Enum: legacy `MediaType` (2 variants, sibling 363) → rework `:domain/model/whatsnew/
 *         MediaType.kt` (clone-not-drift, identity + order required).
 *       - Top-level model: legacy `WhatsNewFeature` (10 fields, sibling 364) → rework
 *         `:domain/model/whatsnew/WhatsNewFeature.kt` (verbatim port — strongest clone-not-drift
 *         in the subdir).
 *       - Wire DTOs: legacy `RemoteWhatsNewFeature` + `WhatsNewResponse` (sibling 366) →
 *         INVERTED-PARALLEL (no rework counterparts; rework :data REACHES INTO legacy types).
 *       - Localization intermediate: legacy `LocalizedFeature` (sibling 365) → INVERTED-
 *         PARALLEL (same reach-in posture).
 *
 *   • Strangler-fig boundary line: cluster203 reveals the FIRST asymmetric strangler-fig in
 *     the §253-sweep history — rework :data does NOT re-declare the wire-format DTOs nor the
 *     localization-intermediate projection. The boundary is at the
 *     `LocalizedFeature → DomainWhatsNewFeature` mapper in WhatsNewRepositoryImpl.kt; everything
 *     "below the line" (fetch, deserialize, locale-resolve) stays in legacy :shared and is
 *     consumed as a library. Contrast with cluster201-202's complaint/ subdir where the
 *     strangler-fig boundary sat at the ComplaintRepository interface and the rework :data
 *     fully re-implemented every layer.
 *
 *   • Doc-lacuna ratio across cluster203: 1-of-4 retains prose (sibling 364, WhatsNewFeature
 *     — the port-delta KDoc is load-bearing); 3-of-4 doc-lacuna (siblings 363, 365, 366 —
 *     pure data shapes). Opposite skew from the complaint/ model trio (3-of-3 retained prose).
 *
 *   • Wave-62 first-leaf cohort (cluster203) maintains the clean LIVE-NOT-STALE posture — no
 *     orphans, no drifted prose, no dead code. All 4 legacy types are architectural-strangler-
 *     fig sources, deliberately preserved as the wire-format and localization library that
 *     the rework :data treats as a black box.
 *
 *   • Cluster204 deferral pointer: 3 files (WhatsNewRemoteDataSource.kt — 126 lines — bridges
 *     RemoteWhatsNewFeature → LocalizedFeature with locale-fallback chain; getDefaultFeatures.kt
 *     — 17 lines — hardcoded fallback WhatsNewFeature list; WhatsNewViewModel.kt — orchestrates
 *     remote-fetch + localize + fallback) close the whatsnew/ subdir entirely. cluster204 will
 *     carry the FULLY-SWEPT register for the 7-file subdir.
 */

