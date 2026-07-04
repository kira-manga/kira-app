package me.manga.kira.presentation.features.complaint.model

// Migration note (Phase 4 batch 4.4): source had an Android-bound getDisplayName(Context) method
// that resolved R.string.* per-case. The display-name lookup is moved to Phase 10 where it's
// re-expressed as a Compose Multiplatform Resources lookup callable from any platform. The enum
// constants and their identity remain identical — only the lookup method moved out.
enum class ComplaintType {
    TECHNICAL,
    LANGUAGES,
    SITES_ADD,
    SITE_ERROR,
    FEATURES,
    CUSTOM,
}

/*
 * Audit-trail postscript (Phase 9.x.cluster201.staleKdocSweep.cascade, Task #657, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster201 leaf 2/4 — :shared/complaint/model/ tier midbody, sibling 358.
 *
 * File-shape note: 15-line enum class — 6 category variants (TECHNICAL, LANGUAGES, SITES_ADD,
 * SITE_ERROR, FEATURES, CUSTOM). Carries surviving Phase 4 batch 4.4 migration prose
 * documenting the Android `getDisplayName(Context)` method removal.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — transitively consumed by all 15 importers of sibling Complaint.kt
 *     (the keystone data class carries `type: ComplaintType` field). Independent direct
 *     references: none — the type is reached only through Complaint instances.
 *
 *   • FULFILLED-PORT — the migration note (`getDisplayName(Context)` removed, R.string.*
 *     lookup moved to Phase 10) IS factually accurate. The enum body carries ONLY the 6
 *     bare variants — no method body, no Android imports, no @StringRes annotations remain.
 *     Per §253 — preserved as point-in-time accurate AND still current.
 *
 *   • FORECAST-NOT-YET-FULFILLED — the migration note's forward-looking claim "moved to Phase
 *     10 (Compose Multiplatform Resources)" remains pending. Verified via grep: no
 *     `Res.string.complaint_type_*` keys exist in the :ui module. The Phase 10 lift would
 *     swap both legacy and rework consumers to `stringResource(Res.string.complaint_type_X)`.
 *
 *   • PARALLEL-CLASS-CLONE-NOT-DRIFT — rework counterpart at `:domain/model/complaint/`
 *     (declared inline in ComplaintSummary.kt L149-156 alongside ComplaintSummary data class)
 *     declares EXACTLY THE SAME 6 variants in EXACTLY THE SAME ORDER. The mapper at
 *     ComplaintListRepositoryImpl.kt relies on `enumValueOf<DomainComplaintType>(legacy.name)`
 *     — order + identity match is REQUIRED for safety. DO NOT reorder or rename variants
 *     without coordinating an explicit mapping table edit in :data/mapper.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure bare-Kotlin enum, no time-API or
 *     Android-API surface remaining.
 */
