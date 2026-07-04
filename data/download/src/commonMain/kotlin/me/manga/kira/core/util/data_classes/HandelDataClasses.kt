package me.manga.kira.core.util.data_classes

import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

// Migration notes (Phase 8.13 batch C):
//   - `android.util.Log` import dropped. No call sites in this file reference Log (object is pure
//     mapping helpers), so no kermit substitution was necessary.
//   - `java.time.LocalDate` -> `kotlinx.datetime.LocalDate` (matches Phase 4/6 model + entity
//     migrations: ChapterItem.date, SavedChapterEntity.date, ChapterNotification.notificationDate
//     are all kotlinx.datetime.LocalDate).
//   - `java.time.LocalDateTime` -> `kotlinx.datetime.LocalDateTime` (HistoryItemD.lastReadDate
//     already migrated in Phase 6).
//   - `LocalDate.now()` -> `Clock.System.todayIn(TimeZone.currentSystemDefault())`.
//   - `LocalDateTime.now()` -> `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())`.
//   - `System.currentTimeMillis()` -> `Clock.System.now().toEpochMilliseconds()` (kotlin.time
//     Clock — same opt-in used in the entity layer).
//
// Phase 9.x.handeldataclasses.componentprune (Task #382): dropped 13 orphan members
// after a 3-pass level-2 reacher-chain audit (anchored FQN imports + bare-identifier
// matches + same-name disambiguation by receiver type). Removed:
//   - `emptyMangaItem()`         — no callers
//   - `emptyMangaInfo()` (fun)   — every reacher has a local inline replacement
//   - `val emptyMangaInfo`       — same; the property variant was never imported FQN
//   - `mapToReaderChaptersString`, `mapFromJson` — never used
//   - `MangaItem.toSavedEntity(...)` — HomeViewModel.toggleManga calls
//     `MangaInfo.toSavedEntity()` (line ~205 below), not the MangaItem variant
//   - `SavedMangaEntity.toMangaItem(...)`, `MangaInfo.toMangaItem()`,
//     `HistoryItemD.toMangaItem()` — all three `.toMangaItem` variants were unreached;
//     per-source repositories define their own `Receiver.toMangaItem` locals
//   - `HistoryItemD.toChapterItem()` — never used; `SavedChapterEntity.toChapterItem()`
//     (used by `toChapterItems`) is the only live `toChapterItem` here
//   - `HistoryItemD.toMangaInfo(chapter)` — `MangaItem.toMangaInfo(chapter)` (used by
//     HomeScreenRoute.onSaveToggle, kept) and `SavedMangaEntity.toMangaInfo(chapters)`
//     (used by LibraryMangaScreenRoute.onMangaBookmarkClick, kept) are the live variants
//   - `ReaderChapters.toHistoryItemD(...)` — never used
//   - `List<MangaItem>.toPopularMangaList()` — every per-source reacher inlines a
//     private local `toPopularMangaList` (documented "Phase 7.1 inline replacement")
// Three transitively-orphan imports removed alongside: `HistoryItemD`, `PopularManga`,
// `kotlinx.datetime.toLocalDateTime`.
//
// Phase 9.x.handeldataclasses.componentprune.cascade (Task #443, 2026-05-28): dropped 1
// cascade-orphan member after the partner retire of `LibraryMangaScreenRoute.kt` in
// Phase 9.x.libdetails.retire.5a (Task #435). The §382 audit-trail entry above preserved
// `SavedMangaEntity.toMangaInfo(chapters)` on the basis of "used by
// LibraryMangaScreenRoute.onMangaBookmarkClick, kept" — that route was deleted in §435.
// 3-pass reacher-chain audit (receiver-anchored `.toMangaInfo(chapters` +
// `SavedMangaEntity\.toMangaInfo` + `::toMangaInfo` callable-ref) returned ZERO live
// source-tree callers post-§435. Original §382 prose preserved verbatim per the §253
// audit-trail-preservation convention — the "kept" verdict was correct at the time;
// this postscript documents the post-§435 cascade revision.
// Removed (cascade-orphan after Task #435):
//   - `SavedMangaEntity.toMangaInfo(chapters: List<ChapterItem>): MangaInfo` — §382 sole
//     reacher chain was `LibraryMangaScreenRoute.onMangaBookmarkClick`; route deleted in §435.
//     The sibling overload `MangaItem.toMangaInfo(chapter: ChapterItem)` remains LIVE — sole
//     reacher `HomeScreenRoute.onSaveToggle` (line 174) is still wired.
object HandelDataClasses {

    fun ChapterDownloadEntity.toChapterEntity(): SavedChapterEntity = SavedChapterEntity(
        id = this.chapterId,
        mangaId = this.mangaId,
        name = this.number,
        number = this.number,
        url = this.url,
        isDownloaded = false,
        isBookmarked = false,
    )

    fun SavedChapterEntity.toChapterDownloadEntity(
        apiName: String,
        title: String,
        initialState: DownloadingState = DownloadingState.QUEUED
    ): ChapterDownloadEntity = ChapterDownloadEntity(
        // id = 0 so that Room will auto-generate
        chapterId = this.id,             // originally a SavedChapterEntity primaryKey
        mangaId = this.mangaId,
        api = apiName,
        mangaTitle = title,
        url = this.url,
        state = initialState,
        progress = 0,
        errorMsg = null,
        number = this.number
    )
}
