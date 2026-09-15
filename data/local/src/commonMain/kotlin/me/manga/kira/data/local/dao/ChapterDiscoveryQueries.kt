package me.manga.kira.data.local.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Room operations inherited by [LibraryDeo] so all refresh entry points share one transaction. */
interface ChapterDiscoveryQueries {
    /** Resolves the exact saved parent; a same-titled manga is not an identity fallback. */
    @Query("SELECT * FROM saved_manga WHERE api = :api AND url = :mangaUrl LIMIT 1")
    suspend fun getDiscoveryManga(api: String, mangaUrl: String): SavedMangaEntity?

    /** Reads the known URLs inside discovery's write transaction (also used by library save). */
    @Query("SELECT url FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun getSavedChapterUrls(mangaId: Long): List<String>

    /** Returns one ID per input; only positive IDs won a new chapter insertion. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long>

    /** A notification failure must abort the chapter discovery, not commit a partial outcome. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDiscoveryNotifications(notifications: List<ChapterNotification>): List<Long>

    /**
     * Atomically discovers chapters and returns only this transaction's newly committed Updates.
     * [chapters] are oldest-first source candidates, not proof of insertion. Existing rows retain
     * their read/download/NEW state, and duplicate candidate URLs produce at most one discovery.
     * [expectedMangaId] prevents an Android snapshot from targeting a removed-and-readded parent.
     * Cancellation and all storage failures propagate; nothing is launched outside the caller.
     */
    @OptIn(ExperimentalTime::class)
    @Transaction
    suspend fun persistChapterDiscoveries(
        api: String,
        mangaUrl: String,
        chapters: List<SavedChapterEntity>,
        expectedMangaId: Long? = null,
    ): List<ChapterNotification> {
        currentCoroutineContext().ensureActive()
        if (chapters.isEmpty()) return emptyList()
        val manga = getDiscoveryManga(api, mangaUrl) ?: return emptyList()
        if (expectedMangaId != null && manga.id != expectedMangaId) return emptyList()
        val known = getSavedChapterUrls(manga.id).toSet()
        val now = Clock.System.now().toEpochMilliseconds()
        val candidates = chapters.distinctBy { it.url }
            .filterNot { it.url in known }
            .map { it.asDiscovery(manga.id, now) }
        return insertDiscoveryOutcome(manga, candidates)
    }
}

/** Runs only inside the generated Room wrapper for persistChapterDiscoveries. */
private suspend fun ChapterDiscoveryQueries.insertDiscoveryOutcome(
    manga: SavedMangaEntity,
    candidates: List<SavedChapterEntity>,
): List<ChapterNotification> {
    if (candidates.isEmpty()) return emptyList()
    val chapterIds = insertChapters(candidates)
    check(chapterIds.size == candidates.size && chapterIds.all { it == -1L || it > 0L }) {
        "chapter_discovery_insert_result_ids"
    }
    val notifications = candidates.zip(chapterIds).mapNotNull { (chapter, id) ->
        if (id > 0L) chapter.notification(manga, id) else null
    }
    currentCoroutineContext().ensureActive()
    if (notifications.isEmpty()) return emptyList()
    val ids = insertDiscoveryNotifications(notifications)
    check(ids.size == notifications.size && ids.all { it > 0L }) { "notification_insert_result_ids" }
    currentCoroutineContext().ensureActive()
    return notifications.zip(ids) { notification, id -> notification.copy(id = id) }
}

private fun SavedChapterEntity.asDiscovery(mangaId: Long, fetchedAt: Long) = SavedChapterEntity(
    mangaId = mangaId,
    name = name,
    number = number,
    url = url,
    date = date,
    isNew = true,
    fetchedAt = fetchedAt,
)

private fun SavedChapterEntity.notification(manga: SavedMangaEntity, chapterId: Long) = ChapterNotification(
    api = manga.api,
    language = manga.language,
    mangaId = manga.id,
    mangaTitle = manga.title,
    mangaImageUrl = manga.imageUrl,
    mangaUrl = manga.url,
    chapterId = chapterId,
    chapterNumber = number,
    chapterUrl = url,
)
