package me.manga.kira.domain.model.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Value-space guards for the Library axis enums. An accidental append/reorder/rename of any value
 * silently changes persisted-pref decoding (the `:data` `String.to*` fallback mappers) and the
 * exhaustive `when` arms in `LibraryViewModel.applyView`; these tests pin the exact value sets.
 */
class LibraryEnumsTest {

    @Test
    fun librarySort_value_space() {
        assertEquals(
            // Native SortType order (sort-sheet chip-render parity): ALPHABETIC, TOTAL_CHAPTERS,
            // LAST_READ, UNREAD_COUNT, DATE_ADDED, RANDOM.
            listOf("ALPHABETIC", "TOTAL_CHAPTERS", "LAST_READ", "UNREAD_COUNT", "DATE_ADDED", "RANDOM"),
            LibrarySort.entries.map { it.name },
        )
    }

    @Test
    fun libraryFilter_value_space() {
        assertEquals(
            // P3 parity fix (audit p3/library, "Filter chip ordering"): native FilterType order is
            // ALL, DOWNLOADED, UNREAD, STARTED, BOOKMARKED, COMPLETED — BOOKMARKED precedes COMPLETED.
            listOf("ALL", "DOWNLOADED", "UNREAD", "STARTED", "BOOKMARKED", "COMPLETED"),
            LibraryFilter.entries.map { it.name },
        )
    }

    @Test
    fun libraryCategory_value_space() {
        assertEquals(
            // P2 parity fix (audit p2/library, "Category tabs ordering"): native FilterTabs order
            // is NAN, WATCHING_NOW, LIKED.
            listOf("NAN", "WATCHING_NOW", "LIKED"),
            LibraryCategory.entries.map { it.name },
        )
    }

    @Test
    fun sortDirection_value_space() {
        assertEquals(
            listOf("ASCENDING", "DESCENDING"),
            SortDirection.entries.map { it.name },
        )
    }

    @Test
    fun gridDensity_value_space() {
        assertEquals(
            listOf("COMPACT", "COMFORTABLE", "SPACIOUS"),
            GridDensity.entries.map { it.name },
        )
    }
}
