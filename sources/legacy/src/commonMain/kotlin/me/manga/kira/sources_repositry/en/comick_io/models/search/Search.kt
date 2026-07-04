package me.manga.kira.sources_repositry.en.comick_io.models.search

/**
 * KMP port: `kotlin.collections.ArrayList` is final on Kotlin/Native, so the original
 * `class Search : ArrayList<SearchItem>()` cannot be subclassed in commonMain.
 *
 * No callers reference this type (only [SearchItem] is imported), so a typealias is
 * the smallest-change port.
 */
typealias Search = ArrayList<SearchItem>
