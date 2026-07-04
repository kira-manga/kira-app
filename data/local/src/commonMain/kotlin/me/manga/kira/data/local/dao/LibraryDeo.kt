package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.home.data.ApiTitle

@Dao
interface LibraryDeo {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManga(manga: SavedMangaEntity): Long

    @Query("SELECT id FROM saved_manga WHERE url = :url LIMIT 1")
    suspend fun getMangaIdByUrl(url: String): Long?
    @Query("SELECT url FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun getSavedChapterUrls(mangaId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChapters(chapters: List<SavedChapterEntity>)

    @Query("SELECT api, title FROM saved_manga")
    fun getSavedMangaApiTitleFlow(): Flow<List<ApiTitle>>


    @Transaction
    suspend fun saveMangaWithChapters(
        manga: SavedMangaEntity,
        chapters: List<SavedChapterEntity>
    ) {
        // 1) upsert your manga (as you already have)
        val inserted = insertManga(manga)
        val mangaId = if (inserted != -1L) inserted
        else getMangaIdByUrl(manga.url)
            ?: return

        // 2) fetch existing chapter URLs
        val existingUrls = getSavedChapterUrls(mangaId).toSet()

        // 3) filter our new batch

        val newChapters = chapters
            .map { it.copy(mangaId = mangaId) }
            .filter { it.url !in existingUrls }


        // 4) only insert the truly new ones
        insertChapters(newChapters)
    }

    @Query("SELECT id FROM saved_manga WHERE title = :title LIMIT 1")
    suspend fun getMangaIdByTitle(title: String): Long?

    // Library identity is the (api, title) pair everywhere else (ApiTitle drives the heart icons),
    // so removal must resolve by api+title — a title-only lookup can hit a same-titled row from a
    // different source and delete the wrong manga + its downloaded files.
    @Query("SELECT id FROM saved_manga WHERE api = :api AND title = :title LIMIT 1")
    suspend fun getMangaIdByApiAndTitle(api: String, title: String): Long?

    // -- Deletion support --
    @Query("DELETE FROM saved_manga WHERE id = :id")
    suspend fun deleteMangaById(id: Long): Int

    @Transaction
    suspend fun removeMangaWithChapters(id: Long) {
        removeAllChaptersForManga(id)
        removeAllDownloadsForManga(id)
        removeAllNotification(id)
        removeHistory(id)
        deleteMangaById(id)
    }

    @Query("DELETE FROM saved_chapters WHERE mangaId = :mangaId")
    suspend fun removeAllChaptersForManga(mangaId: Long)

    // Download-queue rows are NOT a child of saved_manga via FK (see ChapterDownloadEntity),
    // so they must be purged explicitly here or they outlive the manga and resurface the
    // "downloaded" badge/size on re-open. (A CASCADE FK is added separately as belt-and-braces.)
    @Query("DELETE FROM chapter_downloads WHERE mangaId = :mangaId")
    suspend fun removeAllDownloadsForManga(mangaId: Long)

    @Query("DELETE FROM notifications WHERE mangaId = :mangaId")
    suspend fun removeAllNotification(mangaId: Long)
    @Query("DELETE FROM history_items WHERE mangaId = :mangaId")
    suspend fun removeHistory(mangaId: Long)

    // The rework Reader writes history rows keyed by mangaUrl (with mangaId = 0), so the
    // mangaId-based removeHistory above never matches them. Deleting by mangaUrl on library removal
    // clears them regardless of the stored mangaId (so a deleted manga leaves no History entries).
    @Query("DELETE FROM history_items WHERE mangaUrl = :mangaUrl")
    suspend fun removeHistoryByUrl(mangaUrl: String)

    // Belt-and-braces with removeAllNotification(mangaId): also clear notifications by mangaUrl on
    // library removal, so a deleted manga leaves no Notifications/Updates entries regardless of the
    // stored mangaId.
    @Query("DELETE FROM notifications WHERE mangaUrl = :mangaUrl")
    suspend fun removeNotificationsByUrl(mangaUrl: String)


    @Transaction
    suspend fun markChapterAndNotificationRead(chapterId: Long) {
        markChapterAsReadInternal(chapterId)
        markNotificationReadInternal(chapterId)
    }

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id = :chapterId")
    suspend fun markChapterAsReadInternal(chapterId: Long)

    @Query("UPDATE notifications SET isRead = NOT isRead WHERE chapterId = :chapterId")
    suspend fun markNotificationReadInternal(chapterId: Long)
}

/*
 * §253 audit-trail postscript — cluster280 §253 sweep (2026-05-29)
 * ------------------------------------------------------------------
 * Classification: LIVE / LEGACY.
 *
 * LIVE evidence: bound per-platform as an explicit Koin single that pulls the
 * DAO accessor off the database singleton — verified at all three platform
 * modules:
 *   - PlatformModule.android.kt:84  single<LibraryDeo> { get<MangaDatabase>().libraryDeo() }
 *   - PlatformModule.desktop.kt:70  single<LibraryDeo> { get<MangaDatabase>().libraryDeo() }
 *   - PlatformModule.ios.kt:70      single<LibraryDeo> { get<MangaDatabase>().libraryDeo() }
 * The accessor itself is the abstract fun at MangaDatabase.kt:57
 * ("abstract fun libraryDeo(): LibraryDeo"). Consumers are confirmed across
 * BOTH the rework and legacy halves of the strangler fig:
 *   - REWORK :data — LibraryRepositoryImpl.kt:73 ctor field "private val
 *     libraryDeo: LibraryDeo", reached at lines 125 (insertManga), 137 and 147
 *     (removeMangaWithChapters).
 *   - LEGACY :shared — MangaRepository.kt:47 (field "libraryDao: LibraryDeo",
 *     used at :53/:62/:67/:68), library/domain/LibraryRepository.kt:103
 *     (markChapterAndNotificationRead at :164), notifications/domain/
 *     NotificationRepository.kt:56 (markChapterAndNotificationRead at :67).
 *
 * LEGACY status: pre-rework :shared/commonMain Room DAO. NOT a Phase-5.x
 * platform facade — there is no expect/actual fan; the single commonMain
 * @Dao interface is consumed identically on all three targets, and Room's
 * generated implementation is per-target. The cluster184 :data/local/dao
 * 5-leaf sweep (Task #639) deliberately skipped this file as "bare prose-less"
 * (it carries only inline functional step-comments, no block-KDoc), per the
 * ChapterNotification.kt:92 + MangaDao.kt:109 skip notes; cluster280 closes it.
 *
 * Delta-axes:
 *  1. Platform API: androidx.room (KMP) — @Dao, @Insert(onConflict=IGNORE),
 *     @Query, @Transaction. Pure commonMain; no platform-conditional code.
 *  2. Threading/dispatcher: every mutating method is suspend; Room routes work
 *     to its own write executor. The two @Transaction default-method bodies
 *     (saveMangaWithChapters, removeMangaWithChapters, markChapterAndNotificationRead)
 *     run atomically inside a single Room transaction — concurrency safety is
 *     Room's, not the caller's. getSavedMangaApiTitleFlow returns a cold Flow
 *     that Room re-emits on table-change invalidation.
 *  3. Error handling: no typed AppResult wrapper at this layer — the DAO throws
 *     raw Room exceptions; the consuming repositories (rework :data + legacy
 *     :shared facades) own the try/catch-to-Result translation. The
 *     insert-then-lookup idiom in saveMangaWithChapters tolerates the IGNORE
 *     conflict (inserted == -1L) by falling back to getMangaIdByUrl, returning
 *     early on a null id rather than throwing.
 *  4. DI binding mechanism: explicit Koin single<LibraryDeo> per platform (see
 *     evidence above), distinct from the converter sibling in this cluster which
 *     binds via @TypeConverters annotation. The DAO single depends transitively
 *     on the single<MangaDatabase> provided in the same platform module.
 *  5. Cross-DAO transaction note: removeMangaWithChapters chains four queries
 *     across saved_chapters, notifications, history_items, saved_manga. Room
 *     resolves method names within the @Dao scope, so removeAllChaptersForManga
 *     here never reaches ChapterDao's same-named variant (ChapterDao.kt:26-28);
 *     behavioural parity is moot — there is no platform fan to compare.
 *
 * Nested-comment hazard check: the original file body contains zero KDoc/block
 * openers — only single-line slash-slash step comments inside the @Transaction
 * bodies (the numbered "1) upsert ... 4) only insert" notes), none of which open
 * a block comment. This appended block is balanced — exactly one opener and one
 * closer, with no interior comment delimiters in the prose.
 */