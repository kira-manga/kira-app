package me.manga.kira.domain.model.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Contract tests for [ReadingMode] and its [isPaged] extension.
 *
 * The enum `name` is the on-disk persistence key (see the type's wire-format KDoc), so a reorder
 * or rename silently breaks existing user reading-mode preferences — this test pins both.
 * [isPaged] drives the pager-vs-scrolling-column branch in the reader layout.
 */
class ReadingModeTest {

    @Test
    fun enum_names_and_order_are_stable_wire_format() {
        assertEquals(
            listOf("DEFAULT", "RIGHT_TO_LEFT", "LEFT_TO_RIGHT", "VERTICAL", "WEBTOON", "CONTINUOUS_VERTICAL"),
            ReadingMode.entries.map { it.name },
        )
    }

    @Test
    fun isPaged_matches_the_paginated_set() {
        val paged = setOf(
            ReadingMode.DEFAULT,
            ReadingMode.RIGHT_TO_LEFT,
            ReadingMode.LEFT_TO_RIGHT,
            ReadingMode.VERTICAL,
        )
        for (mode in ReadingMode.entries) {
            assertEquals(mode in paged, mode.isPaged, "isPaged mismatch for $mode")
        }
    }

    @Test
    fun scrolling_modes_are_not_paged() {
        assertFalse(ReadingMode.WEBTOON.isPaged)
        assertFalse(ReadingMode.CONTINUOUS_VERTICAL.isPaged)
    }
}
