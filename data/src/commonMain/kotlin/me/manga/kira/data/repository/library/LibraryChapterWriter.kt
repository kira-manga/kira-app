package me.manga.kira.data.repository.library

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.data.identity.WorkAliasComparison
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.mapper.toDomainManga
import me.manga.kira.data.mapper.toNewSavedChapterEntity
import me.manga.kira.data.mapper.toSavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryChapterNotification
import me.manga.kira.domain.model.library.LibraryRefreshReceipt

/** Insert-only discovery for an already-resolved parent; never mutates existing chapter state. */
class LibraryChapterWriter(private val libraryDao: LibraryDeo) {
    internal suspend fun missing(
        session: LibraryOwnerSession,
        owner: SavedMangaEntity,
        fetched: List<Chapter>,
    ): List<Chapter> {
        val saved = libraryDao.getSavedChapterUrls(owner.id).toSet()
        val distinct = fetched.distinctBy { it.url }
        val missing = mutableListOf<Chapter>()
        distinct.forEach { chapter ->
            val prior = saved + missing.map { it.url }
            val aliases = prior.filter { it != chapter.url && session.chapterAliases(owner.api, chapter.url, it) }
            requireLibraryWrite(aliases.isEmpty(), LibraryWriteRejection.CHAPTER_ALIAS_REQUIRES_RECONCILIATION)
            if (chapter.url !in saved) missing += chapter
        }
        return missing
    }

    @OptIn(ExperimentalTime::class)
    internal suspend fun insert(
        owner: SavedMangaEntity,
        chapters: List<Chapter>,
        refreshing: Boolean,
        notify: Boolean,
    ): LibraryRefreshReceipt {
        val ordered = chapters.reversed()
        val now = Clock.System.now().toEpochMilliseconds()
        val rows = ordered.map {
            if (refreshing) it.toNewSavedChapterEntity(owner.id, now) else it.toSavedChapterEntity(owner.id)
        }
        val ids = if (rows.isEmpty()) emptyList() else libraryDao.insertNewLibraryChapters(rows)
        requireLibraryWrite(ids.size == rows.size && ids.all { it > 0 || it == -1L }, LibraryWriteRejection.WRITE_COUNT)
        // Room IGNORE returns -1 for an uncommitted candidate. It must not produce a notification
        // or inflate the receipt, while genuinely inserted siblings remain part of this commit.
        val inserted = ordered.zip(ids).mapNotNull { (chapter, id) ->
            if (id > 0) InsertedLibraryChapter(chapter, id) else null
        }
        val notifications = if (notify) createNotifications(owner, inserted) else emptyList()
        return LibraryRefreshReceipt(owner.savedIdentity(), inserted.size, notifications)
    }

    private suspend fun createNotifications(
        owner: SavedMangaEntity,
        inserted: List<InsertedLibraryChapter>,
    ): List<LibraryChapterNotification> {
        if (inserted.isEmpty()) return emptyList()
        val rows = inserted.map { it.notification(owner) }
        val ids = libraryDao.insertNewLibraryNotifications(rows)
        requireLibraryWrite(ids.size == rows.size && ids.all { it > 0 }, LibraryWriteRejection.WRITE_COUNT)
        return inserted.zip(ids) { chapter, id ->
            LibraryChapterNotification(id, chapter.id, owner.toDomainManga(), chapter.chapter)
        }
    }
}

private data class InsertedLibraryChapter(val chapter: Chapter, val id: Long)

private fun LibraryOwnerSession.chapterAliases(api: String, first: String, second: String): Boolean =
    compare(WorkLocator(api, first), WorkLocator(api, second)) == WorkAliasComparison.DeclaredAlias

private fun InsertedLibraryChapter.notification(owner: SavedMangaEntity) = ChapterNotification(
    api = owner.api,
    language = owner.language,
    mangaId = owner.id,
    mangaTitle = owner.title,
    mangaImageUrl = owner.imageUrl,
    mangaUrl = owner.url,
    chapterId = id,
    chapterNumber = chapter.number,
    chapterUrl = chapter.url,
)
