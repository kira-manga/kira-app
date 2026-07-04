package me.manga.kira.presentation.features.whatsnew.model

/**
 * Ported from upstream `presentation/features/whatsnew/model/WhatsNewFeature.kt`.
 *
 * Deltas vs source:
 *   1. `imageRes: Int?` → `imageResName: String?`. KMP compose-resources are addressed by name
 *      (e.g. `Res.drawable.new_su` → "new_su"); the Android-only `@DrawableRes Int` token cannot
 *      survive in commonMain. Resolution happens at the UI layer with compose-resources.
 *   2. `imageResList: List<Int>` → `imageResNameList: List<String>` for the same reason.
 *   3. `@DrawableRes` annotation dropped (it lives in `androidx.annotation`).
 *
 * Lives in `:shared/commonMain` so the WhatsNewViewModel (also in :shared) can construct it
 * without depending on the `:composeApp` module.
 */
data class WhatsNewFeature(
    val title: String,
    val description: String,
    val mediaType: MediaType,
    /** Compose-resources drawable name (no extension), e.g. `"new_su"`. Replaces source's `imageRes: Int?`. */
    val imageResName: String? = null,
    /** List of compose-resources drawable names. Replaces source's `imageResList: List<Int>`. */
    val imageResNameList: List<String> = emptyList(),
    val imageUrl: String? = null,
    val imageUrlList: List<String> = emptyList(),
    val videoUrl: String? = null,
    val isNew: Boolean = false,
    val version: String? = null,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster203.staleKdocSweep.cascade, Task #659, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster203 leaf 2/4 — :shared/whatsnew/model/ tier closer, sibling 364. Cumulative
 * §253-postscript count = 89 leaves with this commit.
 *
 * File-shape note: 29-line data class — 10 fields (title, description, mediaType, imageResName,
 * imageResNameList, imageUrl, imageUrlList, videoUrl, isNew, version). 5 of 10 default-valued.
 * Carries an 11-line block-KDoc header documenting Phase 14.x KMP port deltas from the
 * Android-source variant, plus 2 inline field-KDocs (imageResName, imageResNameList).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — consumed directly by:
 *       - WhatsNewViewModel.kt (sibling — cluster204 cohort) — `_features: MutableStateFlow<List<
 *         WhatsNewFeature>>` cell; built by the seed-from-defaults path + remote-fetch hydration.
 *       - getDefaultFeatures.kt (sibling — cluster204 cohort) — hardcoded fallback list of
 *         WhatsNewFeature instances used when remote fetch fails.
 *       - WhatsNewScreen.kt + WhatsNewContent.kt (:ui presentation — outside this sweep) —
 *         consume `WhatsNewFeature.mediaType` + `imageResName` + `imageUrl` etc. for rendering.
 *
 *   • FULFILLED-PORT — the file's OWN block-KDoc documents the Phase 14.x KMP port delta:
 *     Android-source `imageRes: Int?` (@DrawableRes) → KMP `imageResName: String?` (compose-
 *     resources name token). The `@DrawableRes` annotation was dropped (lives in `androidx.annotation`).
 *     Port verified — `MangaSource:` not applicable here; this is a Phase 14.x rework-to-rework
 *     port within :shared, not a legacy-to-rework strangler-fig. Per §253 — preserved (the
 *     KDoc IS current and load-bearing for future readers).
 *
 *   • PARALLEL-CLASS-CLONE-NOT-DRIFT — rework counterpart at `:domain/model/whatsnew/WhatsNewFeature.kt`
 *     declares EXACTLY THE SAME 10 fields in EXACTLY THE SAME ORDER (verbatim port — verified
 *     in cluster203 scout pass). The rework :data WhatsNewRepositoryImpl.kt translates between
 *     the two via a `LocalizedFeature → DomainWhatsNewFeature` mapper that fills field-by-field —
 *     identity + order match REQUIRED. Strongest clone-not-drift evidence in the whatsnew/ subdir.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 in-package reference: `mediaType: MediaType` field
 *     (sibling 363, cluster203 leaf 1/4). Otherwise zero imports — pure data class.
 *
 *   • DOC-CURRENT-NOT-STALE — the block-KDoc's "Lives in `:shared/commonMain` so the
 *     WhatsNewViewModel (also in :shared) can construct it without depending on the
 *     `:composeApp` module" statement remains accurate post-Phase 14.x. WhatsNewViewModel
 *     remains in :shared (deferred to cluster204); module placement decision unchanged.
 */

