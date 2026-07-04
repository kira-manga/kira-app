package me.manga.kira.domain.model.complaint

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests guarding the [ComplaintType] / [ComplaintStatus] value sets.
 *
 * The `:data` FeedbackRepositoryImpl maps domain -> legacy via `LegacyComplaintType.valueOf(type.name)`,
 * so the names are load-bearing wire-format: a rename breaks that mapper at runtime (no compile
 * error), and a reorder breaks any ordinal-based persistence. (The list/action mappers —
 * ComplaintListRepositoryImpl etc. — instead use exhaustive `when` branches, which are
 * compile-checked.) These tests pin the exact 6 type + 8 status values verified against the source.
 */
class ComplaintEnumsTest {

    @Test
    fun complaintType_names_and_order() {
        assertEquals(
            listOf("TECHNICAL", "LANGUAGES", "SITES_ADD", "SITE_ERROR", "FEATURES", "CUSTOM"),
            ComplaintType.entries.map { it.name },
        )
    }

    @Test
    fun complaintStatus_names_and_order() {
        assertEquals(
            listOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "PLANNED", "PINNED", "UNKNOWN", "NOT_PLANNED"),
            ComplaintStatus.entries.map { it.name },
        )
    }
}
