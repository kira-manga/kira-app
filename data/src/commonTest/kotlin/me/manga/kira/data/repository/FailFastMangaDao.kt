package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.local.entity.SavedMangaMetadataUpdate
import me.manga.kira.presentation.features.library.data.MangaChapterMetrics

/** Legacy tests keep their existing overrides; newly reached DAO methods must fail, never succeed. */
internal object FailFastMangaDao : MangaDao {
    override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = unused()
    override suspend fun updateManga(manga: SavedMangaEntity): Int = unused()
    override suspend fun update(manga: SavedMangaEntity): Unit = unused()
    override fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>> = unused()
    override suspend fun getApiByMangaId(mangaId: Long): String? = unused()
    override suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long): Unit = unused()
    override suspend fun getIdByApiAndTitle(api: String, title: String): Long? = unused()
    override suspend fun getIdByApiAndUrl(api: String, mangaUrl: String): Long? = unused()
    override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = unused()
    override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = unused()
    override suspend fun getMangaIdsByApi(api: String): List<Long> = unused()
    override suspend fun toggleLiked(mangaId: Long): Unit = unused()
    override suspend fun toggleWatchingNow(mangaId: Long): Unit = unused()
    override suspend fun updateSavedCover(mangaId: Long, imageUrl: String): Unit = unused()
    override suspend fun updateHistoryCover(mangaId: Long, mangaUrl: String, imageUrl: String): Unit = unused()
    override suspend fun updateNotificationCover(mangaId: Long, imageUrl: String): Unit = unused()
    override suspend fun getMangaByExactUrl(url: String): SavedMangaEntity? = unused()
    override suspend fun getMangaByExactOwner(id: Long, api: String, url: String): SavedMangaEntity? = unused()
    override suspend fun updateMetadataColumns(update: SavedMangaMetadataUpdate): Int = unused()
    override suspend fun updateCoverForExactOwner(id: Long, api: String, url: String, imageUrl: String): Int = unused()
    override suspend fun toggleLikedForExactOwner(id: Long, api: String, url: String): Int = unused()
    override suspend fun toggleWatchingForExactOwner(id: Long, api: String, url: String): Int = unused()
    override suspend fun updateOpenedForExactOwner(id: Long, api: String, url: String, timestamp: Long): Int = unused()

    private fun unused(): Nothing = error("Unexpected MangaDao call: add an explicit behavioral fake")
}
