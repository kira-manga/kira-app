package me.manga.kira.presentation.features.whatsnew.data

data class LocalizedFeature(
    val title: String,
    val description: String,
    val mediaType: String,
    val imageRes: String?,
    val imageList: List<String>,
    val imageUrl: String?,
    val videoUrl: String?,
    val isNew: Boolean,
    val version: String?,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster203.staleKdocSweep.cascade, Task #659, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster203 leaf 3/4 — :shared/whatsnew/data/ tier opener, sibling 365. Cumulative
 * §253-postscript count = 90 leaves with this commit.
 *
 * File-shape note: 13-line data class — 9 fields (title, description, mediaType, imageRes,
 * imageList, imageUrl, videoUrl, isNew, version). All non-default. No KDoc header. Internal
 * intermediate projection from `RemoteWhatsNewFeature` (sibling 366) for a single resolved
 * language — Map<String,String> title/description collapse to plain String here after a
 * locale-resolution pass in WhatsNewRemoteDataSource.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE — directly consumed by THREE distinct sites:
 *       1. WhatsNewRemoteDataSource.kt (sibling — cluster204 cohort) — emits `LocalizedFeature`
 *          from `getLocalizedFeature(remote, lang)` via per-field language-fallback resolution.
 *       2. WhatsNewRepositoryImpl.kt (:data — rework strangler-fig consumer) — calls
 *          `remoteDataSource.getLocalizedFeature(remote, lang)` per L118 and maps each
 *          LocalizedFeature → DomainWhatsNewFeature for the rework :ui consumers.
 *       3. WhatsNewReworkModule.kt (:composeApp — Koin wiring) — type-imported (via
 *          LocalizedFeature transitively re-exported through the WhatsNewRemoteDataSource
 *          signature) when binding the rework graph.
 *     The legacy `:shared` data layer is the ONLY origin of LocalizedFeature; the rework
 *     :data path REACHES INTO this legacy type rather than re-declaring it. Strongest
 *     STRANGLER-FIG-SOURCE evidence in the whatsnew/data/ subdir.
 *
 *   • INVERTED-PARALLEL — no same-named rework counterpart at `:data/model/` or `:domain/model/`.
 *     The rework :data layer consumes the legacy `LocalizedFeature` type directly via the
 *     WhatsNewRemoteDataSource bridge — no re-declaration, no separate DTO. This is the
 *     deliberate strangler-fig posture: the rework :data layer treats legacy :shared as a
 *     "still-functioning fetch + localize" library and only owns the DomainWhatsNewFeature
 *     conversion at its outbound boundary.
 *
 *   • DOC-LACUNA — no KDoc header. Per §253 — preserved (the field names + types are self-
 *     describing; adding a synthetic header would falsify the audit trail). The class is a
 *     short-lived intermediate inside the localization pipeline; its semantic role is implied
 *     by the call-site shape rather than narrated in-file.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure bare-Kotlin data class.
 *
 *   • FIELD-SHAPE-MATCHES-WIRE-FORMAT — `mediaType: String` (not the typed MediaType enum,
 *     sibling 363). This is the WIRE-FORMAT representation: LocalizedFeature is the post-
 *     localization-resolution but pre-type-mapping projection. The downstream rework :data
 *     mapper at WhatsNewRepositoryImpl.kt L118+ converts `LocalizedFeature.mediaType` (String)
 *     → DomainMediaType (enum) via name-token bridge. DO NOT tighten the type to MediaType
 *     here — it would break the wire-format-stays-stringly-typed boundary intentionally
 *     preserved between the legacy data layer and the rework :data layer.
 */

