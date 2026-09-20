package me.manga.kira.domain.model.library

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.SavedWorkIdentity

/** Returned only after the complete metadata/chapter/Updates transaction commits. */
data class LibraryRefreshReceipt(
    val owner: SavedWorkIdentity,
    val addedChapters: Int,
    val notifications: List<LibraryChapterNotification>,
)

/** Committed Updates entry for optional platform display; displaying must not write it again. */
data class LibraryChapterNotification(
    val notificationId: Long,
    val chapterId: Long,
    val manga: Manga,
    val chapter: Chapter,
)
