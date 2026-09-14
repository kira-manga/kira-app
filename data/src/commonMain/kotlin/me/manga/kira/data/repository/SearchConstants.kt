package me.manga.kira.data.repository

/**
 * Admitted cold source flows per multi-source query collection, including client lookup and search.
 * Four is a mobile-oriented admission policy, not a process-wide HTTP or thread limit.
 */
internal const val MULTI_SOURCE_SEARCH_CONCURRENCY = 4
