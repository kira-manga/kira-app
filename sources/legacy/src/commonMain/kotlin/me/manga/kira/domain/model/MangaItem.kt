package me.manga.kira.domain.model

import kotlinx.serialization.Serializable

// Migration note (Phase 4 batch 4.2): @Parcelize + Parcelable dropped — Android-only API; replaced
// with @Serializable.
@Serializable
data class MangaItem(
    val api: String,
    val language: String,
    val title: String,
    val url: String,
    val imageUrl: String,
    val rating: Int?,
    val chapters: List<ChapterItem>?,
    val genres: List<String>,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster212.staleKdocSweep.cascade, Task #668, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster212 leaf 1/4 — :shared/domain/model/ tier OPENER, sibling 393. Cumulative
 * §253-postscript count = 118 leaves with this commit.
 *
 * File-shape note: 17-line file — `MangaItem` data class with 8 fields (api + language + title +
 * url + imageUrl + nullable rating + nullable chapters: List<ChapterItem> + genres: List<String>)
 * + 2-line class-level Migration-note prose (lines 5-6) carrying Phase 4 batch 4.2
 * @Parcelize → @Serializable port lineage.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — wide-reach DTO — 63 reacher count across the repo:
 *       1. sources_repositry per-language Repository implementations (ar/+en/+es/+fr/+in/+it/+pt/
 *          +ru/+tr/+ az.) emit MangaItem from search/home/popular fetches.
 *       2. presentation/features/home VM + composeApp Home/Search composables consume
 *          MangaItem for grid + carousel rendering.
 *       3. core/util/data_classes/HandelDataClasses.kt re-exposes MangaItem through the
 *          legacy HandelDataClasses umbrella for downstream legacy callers.
 *       4. presentation/common/viewmodel/MangaViewModel.kt — legacy shared VM that bridges
 *          MangaItem flows between sources_repositry + Home features.
 *
 *   • MIGRATION-NOTE-FULFILLED-AND-LOAD-BEARING — 2-line class-level prose (lines 5-6)
 *     documents Phase 4 batch 4.2 @Parcelize/Parcelable → @Serializable port. PRESERVE — the
 *     @Serializable annotation IS the fulfillment marker; the prose explains why the legacy
 *     Android-only Parcelable was dropped (KMP-portability requirement). Useful when iOS/Desktop
 *     contributors wonder why the model lacks @Parcelize.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: kotlinx.serialization.Serializable. LIVE.
 *     Plus 1 intra-package symbol reference: ChapterItem from the same domain.model package.
 */
