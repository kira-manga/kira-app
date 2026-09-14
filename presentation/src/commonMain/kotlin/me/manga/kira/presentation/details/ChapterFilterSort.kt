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
 * The pure-domain [me.manga.kira.domain.model.Chapter] has no surrogate `id`, so `ID` uses fetched/
 * source order. `LAST_READ_DATE` uses the saved `lastReadAtEpochMillis`, independently of the read
 * flag. `0` means unknown time; ordinary positive timestamps sort as more recent.
 *
 * Keys are descending before the shared ascending reversal. Equal read times keep source order
 * descending and reverse it ascending, so all-zero history matches `ID` in either direction.
 */
enum class ChapterSortType {
    ID,
    NUMBER,
    DATE,
    LAST_READ_DATE,
}
