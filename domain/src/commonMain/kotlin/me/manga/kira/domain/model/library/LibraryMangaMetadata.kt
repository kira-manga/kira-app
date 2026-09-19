package me.manga.kira.domain.model.library

import kotlin.time.Instant

/** Persisted library dates, independent of fetched source metadata. */
data class LibraryActivity(
    val addedAt: Instant,
    val lastOpenedAt: Instant,
    val lastReadAt: Instant?,
)

/** Chapter aggregates projected by the local store, not calculated by the view. */
data class LibraryChapterCounts(
    val total: Int,
    val unread: Int,
    val downloaded: Int,
    val bookmarked: Int,
)

/** User-owned flags; metadata refreshes never replace them. */
data class LibraryAffinity(
    val isLiked: Boolean,
    val isWatchingNow: Boolean,
)
