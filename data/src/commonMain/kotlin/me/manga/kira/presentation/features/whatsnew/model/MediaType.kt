package me.manga.kira.presentation.features.whatsnew.model

enum class MediaType {
    IMAGE,
    VIDEO,
}

/*
 * Audit-trail postscript (Phase 9.x.cluster203.staleKdocSweep.cascade, Task #659, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster203 leaf 1/4 — :shared/whatsnew/model/ tier opener, sibling 363. Wave-62 opens with
 * the legacy :shared/whatsnew/ subdirectory sweep — model/ pair + data/ leading pair (4 files,
 * cluster203 cohort). Cumulative §253-postscript count = 88 leaves with this commit.
 *
 * File-shape note: 6-line enum class — 2 variants (IMAGE, VIDEO). Smallest legacy whatsnew/
 * file by far. No KDoc, no imports, no annotations.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — transitively consumed by legacy WhatsNewFeature.kt (sibling 364)'s
 *     `mediaType: MediaType` field. Direct independent reference: legacy WhatsNewRemoteData
 *     Source / LocalizedFeature mapping path (string ↔ enum via `MediaType.valueOf(...)`
 *     fallback chain — to be classified in cluster204 leaf 1/3 for the data source).
 *
 *   • PARALLEL-CLASS-CLONE-NOT-DRIFT — rework counterpart at
 *     `:domain/model/whatsnew/MediaType.kt` declares EXACTLY THE SAME 2 variants in
 *     EXACTLY THE SAME ORDER. The :data mapper at WhatsNewRepositoryImpl.kt routes through
 *     `enumValueOf<DomainMediaType>(legacy.name)` (or equivalent name-token bridge) —
 *     identity + order match REQUIRED. Same posture as the complaint subdir's enum pair
 *     (siblings 358-359 — ComplaintType + ComplaintStatus, cluster201 cohort).
 *
 *   • DOC-LACUNA — no KDoc header. Per §253 — preserved (adding a synthetic header would
 *     falsify the audit trail; the 2-variant enum is self-documenting).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure bare-Kotlin enum.
 */
