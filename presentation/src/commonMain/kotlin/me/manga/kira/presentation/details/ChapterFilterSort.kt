package me.manga.kira.presentation.details

/**
 * Chapter-list filter options for the Details screen.
 *
 * Native parity (`LibraryDetailsViewModel.FilterType`, library_details — folded into the rework
 * Details screen): the five filters the native chapter filter bottom sheet exposes. Applied over
 * `MangaDetails.chapters` in the [DetailsViewModel] reducer the same way native applied them over
 * `getChaptersByMangaId(...)` (LibraryDetailsViewModel.kt:104-110).
 *
 * `READED` keeps the native spelling/key (`filter_readed`) verbatim for resource-key parity.
 */
enum class ChapterFilterType {
    ALL,
    DOWNLOADED,
    UNREAD,
    READED,
    BOOKMARKED,
}

/**
 * Chapter-list sort keys for the Details screen.
 *
 * Native parity (`LibraryDetailsViewModel.SortType`, library_details): four sort keys, combined
 * with [DetailsState.sortAscending] for direction. Native sorts the saved-chapter list by
 * `id` / `number.toDoubleOrNull()` / `date` / `lastReadDate` (LibraryDetailsViewModel.kt:113-118).
 *
 * The pure-domain [me.manga.kira.domain.model.Chapter] carries no surrogate `id` or
 * `lastReadDate` (those live on the Room `SavedChapterEntity`), so in the rework the reducer
 * approximates native's `ID` sort with the chapters' fetched/source order (the same order native's
 * autoincrement `id` reflects, since rows are inserted in source order) and `LAST_READ_DATE` falls
 * back to that same source order when no read timestamp is available on the domain model.
 */
enum class ChapterSortType {
    ID,
    NUMBER,
    DATE,
    LAST_READ_DATE,
}
