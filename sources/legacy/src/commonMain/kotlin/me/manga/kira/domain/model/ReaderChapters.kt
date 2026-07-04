package me.manga.kira.domain.model

import kotlinx.serialization.Serializable

// Migration note (Phase 4 batch 4.2): @Parcelize + Parcelable dropped — Android-only API.
// @Serializable was already present in source — kept.
@Serializable
data class ReaderChapters(
    val api: String,
    val language: String,
    val chapterNumber: String,
    val chapterName: String,
    val isDownloaded: Boolean = false,
    val url: String,
    val isBookmarked: Boolean = false,
    val chapterId: Long = 0,
    val mangaId: Long = 0,
    val mangaName: String,
    val localImagePaths: List<String> = emptyList(),
)

/*
 * Audit-trail postscript (Phase 9.x.cluster212.staleKdocSweep.cascade, Task #668, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster212 leaf 2/4 — :shared/domain/model/ tier, sibling 394. Cumulative §253-postscript
 * count = 119 leaves with this commit.
 *
 * File-shape note: 20-line file — `ReaderChapters` data class with 11 fields (api + language +
 * chapterNumber + chapterName + default-false isDownloaded + url + default-false isBookmarked +
 * default-0 chapterId: Long + default-0 mangaId: Long + mangaName + default-empty
 * localImagePaths: List<String>) + 2-line class-level Migration-note prose (lines 5-6) carrying
 * Phase 4 batch 4.2 @Parcelize-drop port lineage (@Serializable was pre-existing).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow-reach reader-domain DTO — 9 reacher count across the repo:
 *       1. presentation/features/reader/ui/viewmodel/ReaderViewModel.kt — legacy reader VM
 *          carrying the ReaderChapters list state.
 *       2. presentation/features/reader/data/ReaderItem.kt — sealed-class-hosted ReaderChapters
 *          variant wrapper (LIVE per cluster205 sibling 360).
 *       3. composeApp reader UI fan-out (ReaderScreen + ControlOverlay + errorCard +
 *          NextChapterCard) — consume ReaderChapters for chapter-list display + next-chapter
 *          navigation.
 *       4. ChapterImagesScreenRoute.kt — legacy route adapter projects ReaderChapters into the
 *          screen's parameter tuple.
 *       5. core/util/data_classes/HandelDataClasses.kt — legacy umbrella re-export.
 *       6. presentation/common/viewmodel/SharedChaptersViewModel.kt — legacy shared VM bridging
 *          ReaderChapters between fetch + reader features.
 *
 *   • MIGRATION-NOTE-FULFILLED-AND-LOAD-BEARING — 2-line class-level prose (lines 5-6)
 *     documents Phase 4 batch 4.2 @Parcelize-drop port. Distinct nuance from MangaItem
 *     (sibling 393): @Serializable was ALREADY present in legacy source — only @Parcelize
 *     needed removing. PRESERVE — the "kept" notation is the load-bearing detail.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: kotlinx.serialization.Serializable. LIVE.
 */
