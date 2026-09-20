package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaClassificationMetadata
import me.manga.kira.data.local.entity.SavedMangaContentMetadata
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.local.entity.SavedMangaMetadataUpdate
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.assertEquals

/** Actual Room adapters, with the production factory's per-connection FK guarantee retained. */
internal class MangaMetadataFixture {
    val db = Room.inMemoryDatabaseBuilder<MangaDatabase>()
        .setDriver(MetadataForeignKeysDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    val dao: MangaDao get() = db.mangaDao()

    fun close() = db.close()

    suspend fun seed(manga: SavedMangaEntity = metadataManga()): MetadataSeed {
        val mangaId = db.backupDao().insertMangaRow(manga)
        check(mangaId > 0)
        val savedManga = manga.copy(id = mangaId)
        val chapter = metadataChapter(savedManga)
        val savedChapter = chapter.copy(id = db.backupDao().insertChapterRow(chapter))
        val download = metadataDownload(savedManga, savedChapter)
        val savedDownload = download.copy(id = db.chapterDownloadingDao().insert(download))
        return MetadataSeed(savedManga, savedChapter, savedDownload)
    }

    suspend fun writeLocalState(manga: SavedMangaEntity) {
        assertEquals(1, dao.toggleLikedForExactOwner(manga.id, manga.api, manga.url))
        assertEquals(1, dao.toggleWatchingForExactOwner(manga.id, manga.api, manga.url))
        assertEquals(1, dao.updateOpenedForExactOwner(manga.id, manga.api, manga.url, 999L))
    }

    suspend fun assertChildrenUnchanged(seed: MetadataSeed) {
        assertEquals(seed.chapter, db.chapterDao().getChapterByIdSuspend(seed.chapter.id))
        assertEquals(seed.download, db.chapterDownloadingDao().getDownloadByChapter(seed.chapter.id))
    }
}

private class MetadataForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName).also { connection ->
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.prepare("PRAGMA foreign_keys").use { statement ->
            check(statement.step())
            check(statement.getLong(0) == 1L)
        }
    }
}

internal data class MetadataSeed(
    val manga: SavedMangaEntity,
    val chapter: SavedChapterEntity,
    val download: ChapterDownloadEntity,
)

internal fun metadataManga(
    url: String = "https://current.test/work/one",
    api: String = "source",
    language: String = "en",
) = SavedMangaEntity(
    api = api,
    language = language,
    url = url,
    imageUrl = "https://images.test/before.jpg",
    title = "Same display title",
    description = "Before description",
    author = "Before author",
    status = "ongoing",
    rating = "4.1",
    genres = listOf("before"),
    savedTimestamp = 101L,
    lastOpenTimestamp = 202L,
)

private fun metadataChapter(manga: SavedMangaEntity) = SavedChapterEntity(
    mangaId = manga.id,
    name = "Chapter one",
    number = "1",
    url = "${manga.url}/chapter/one",
    date = null,
    isDownloaded = true,
    isBookmarked = true,
    isRead = true,
    isNew = true,
    lastReadPage = 4,
    lastReadDate = 303L,
    localImagePaths = listOf("/offline/${manga.id}/one.cbz"),
    fetchedAt = 404L,
)

private fun metadataDownload(manga: SavedMangaEntity, chapter: SavedChapterEntity) = ChapterDownloadEntity(
    number = chapter.number,
    chapterId = chapter.id,
    mangaId = manga.id,
    api = manga.api,
    mangaTitle = manga.title,
    url = chapter.url,
    state = DownloadingState.SUCCESS,
    progress = 100,
    sizeBytes = 555L,
)

internal fun metadataPatch(id: Long) = SavedMangaMetadataUpdate(
    id = id,
    content = SavedMangaContentMetadata(
        title = "Renamed title",
        description = "Fetched description",
        author = "Fetched author",
        imageUrl = "https://images.test/fetched.jpg",
    ),
    classification = SavedMangaClassificationMetadata(
        language = "ar",
        status = "finished",
        rating = null,
        genres = listOf("new", "fantasy"),
    ),
)

internal fun expectedMetadata(local: SavedMangaEntity, patch: SavedMangaMetadataUpdate) = local.copy(
    title = patch.content.title,
    description = patch.content.description,
    author = patch.content.author,
    imageUrl = patch.content.imageUrl,
    language = patch.classification.language,
    status = patch.classification.status,
    rating = patch.classification.rating,
    genres = patch.classification.genres,
)
