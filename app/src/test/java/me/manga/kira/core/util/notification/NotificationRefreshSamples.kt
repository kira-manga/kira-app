package me.manga.kira.core.util.notification

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import org.junit.Assert.assertEquals

/** Oldest-first fixture candidates become source-order details; supplied stale owners stay stale. */
internal fun notificationRefreshRequest(
    manga: SavedMangaEntity,
    chapters: List<SavedChapterEntity>,
): LibraryRefreshRequest {
    val owner = SavedWorkIdentity(manga.id, WorkLocator(manga.api, manga.url))
    val details = MangaDetails(
        api = manga.api, language = manga.language, title = manga.title, url = manga.url,
        coverUrl = "", description = manga.description, author = manga.author.orEmpty(),
        rating = manga.rating.orEmpty(), status = manga.status, genres = manga.genres,
        chapters = chapters.asReversed().map { Chapter(it.number, it.name, it.url, it.date, false, false) },
    )
    return LibraryRefreshRequest(owner, FetchedWorkDetails(owner.locator, details))
}

/** Success-only convenience for existing display/atomicity tests, not a production fallback. */
internal suspend fun ChapterNotificationHelper.persistFixtureNotifications(
    manga: SavedMangaEntity,
    chapters: List<SavedChapterEntity>,
): LibraryRefreshReceipt = persistNewChapterNotifications(notificationRefreshRequest(manga, chapters)).requireReceipt()

internal fun AppResult<LibraryRefreshReceipt>.requireReceipt(): LibraryRefreshReceipt = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw AssertionError("Expected committed notification receipt, got $error")
}

/** Compare returned display data to an independently read real Updates row; never invent a row. */
internal fun assertNotificationPayload(
    ownerId: Long,
    notification: LibraryChapterNotification,
    stored: ChapterNotification,
) {
    assertEquals(notification.notificationId, stored.id)
    assertEquals(notification.chapterId, stored.chapterId)
    assertEquals(ownerId, stored.mangaId)
    assertEquals(notification.manga.api, stored.api)
    assertEquals(notification.manga.url, stored.mangaUrl)
    assertEquals(notification.manga.language, stored.language)
    assertEquals(notification.manga.title, stored.mangaTitle)
    assertEquals(notification.manga.coverUrl, stored.mangaImageUrl)
    assertEquals(notification.chapter.number, stored.chapterNumber)
    assertEquals(notification.chapter.url, stored.chapterUrl)
}
