package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDateTime
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.presentation.features.library.data.MangaChapterMetrics
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Stateful in-memory DAO / port fakes shared by the sources-migration tests
 * ([SourceUrlMigratorTest], [SourceCatalogSyncRepositoryTest]). Unlike the recording-only fakes in
 * `LibraryRepoTestFakes`, these actually persist rows so a migration pass can be read back and
 * asserted (host swapped, path preserved, untouched rows untouched).
 */

internal val testDispatchers =
    object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

internal fun mangaRow(
    id: Long,
    api: String,
    url: String,
    imageUrl: String = "https://img.$api.test/cover/$id.jpg",
    title: String = "manga-$id",
): SavedMangaEntity =
    SavedMangaEntity(
        id = id,
        api = api,
        language = "(AR)",
        url = url,
        imageUrl = imageUrl,
        title = title,
        description = "",
        status = "ongoing",
        rating = null,
        genres = emptyList(),
    )

internal fun chapterRow(
    id: Long,
    mangaId: Long,
    url: String,
): SavedChapterEntity = SavedChapterEntity(id = id, mangaId = mangaId, name = "ch-$id", number = "$id", url = url)

internal fun historyRow(
    id: Long,
    api: String,
    mangaUrl: String,
    chapterUrl: String,
    mangaImageUrl: String = "https://img.$api.test/cover/$id.jpg",
): HistoryItemD =
    HistoryItemD(
        id = id,
        api = api,
        language = "(AR)",
        mangaId = id,
        mangaUrl = mangaUrl,
        mangaTitle = "manga-$id",
        mangaImageUrl = mangaImageUrl,
        chapterUrl = chapterUrl,
        chapterTitle = "ch-$id",
        isDownloaded = false,
    )

internal fun notificationRow(
    id: Long,
    api: String,
    mangaUrl: String,
    chapterUrl: String,
    mangaImageUrl: String = "https://img.$api.test/cover/$id.jpg",
): ChapterNotification =
    ChapterNotification(
        id = id,
        api = api,
        language = "(AR)",
        mangaId = id,
        mangaTitle = "manga-$id",
        mangaImageUrl = mangaImageUrl,
        mangaUrl = mangaUrl,
        chapterId = id,
        chapterNumber = "$id",
        chapterUrl = chapterUrl,
    )

internal fun sourceRow(
    name: String,
    baseUrl: String,
    imageBaseUrl: String = "",
    isEnabled: Boolean = true,
    priority: Int = 0,
    language: String = "(AR)",
    baseVersion: Int = 0,
    imageUrlVersion: Int = 0,
): SourcesEntity =
    SourcesEntity(
        name = name,
        isEnabled = isEnabled,
        priority = priority,
        language = language,
        baseUrl = baseUrl,
        baseVersion = baseVersion,
        imageBaseUrl = imageBaseUrl,
        imageUrlVersion = imageUrlVersion,
    )

internal class StatefulMangaDao(
    seed: List<SavedMangaEntity> = emptyList(),
) : MangaDao {
    val rows = seed.toMutableList()
    val updates = mutableListOf<SavedMangaEntity>()

    /**
     * Test hook: return a throwable to fail that row's `update()` (simulates the UNIQUE(url) ABORT
     * Room throws when a host-move rewrite collides with another saved_manga row's url).
     */
    var updateFailure: ((SavedMangaEntity) -> Throwable?)? = null

    override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = rows.filter { it.api == api }

    override suspend fun getMangaIdsByApi(api: String): List<Long> = rows.filter { it.api == api }.map { it.id }

    override suspend fun update(manga: SavedMangaEntity) {
        updateFailure?.invoke(manga)?.let { throw it }
        replace(manga)
        updates += manga
    }

    override suspend fun updateManga(manga: SavedMangaEntity): Int {
        replace(manga)
        return 1
    }

    override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = rows.firstOrNull { it.id == mangaId }

    private fun replace(manga: SavedMangaEntity) {
        val idx = rows.indexOfFirst { it.id == manga.id }
        if (idx >= 0) rows[idx] = manga else rows += manga
    }

    // --- unused surface ---------------------------------------------------------------------------
    override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = flowOf(emptyList())

    override fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>> = flowOf(rows.toList())

    override suspend fun getApiByMangaId(mangaId: Long): String? = rows.firstOrNull { it.id == mangaId }?.api

    override suspend fun updateLastOpenTimestamp(
        mangaId: Long,
        timestamp: Long,
    ) = Unit

    override suspend fun getIdByApiAndTitle(
        api: String,
        title: String,
    ): Long? = rows.firstOrNull { it.api == api && it.title == title }?.id
}

internal class StatefulChapterDao(
    seed: List<SavedChapterEntity> = emptyList(),
) : FakeChapterDao() {
    val rows = seed.toMutableList()
    val updates = mutableListOf<SavedChapterEntity>()

    override suspend fun getChaptersByMangaIdR(mangaId: Long) = rows.filter { it.mangaId == mangaId }

    override suspend fun updateChapter(chapter: SavedChapterEntity) {
        val idx = rows.indexOfFirst { it.id == chapter.id }
        if (idx >= 0) rows[idx] = chapter
        updates += chapter
    }
}

internal class StatefulHistoryDao(
    seed: List<HistoryItemD> = emptyList(),
) : HistoryDao {
    val rows = seed.toMutableList()
    val updates = mutableListOf<HistoryItemD>()

    override suspend fun getHistoryByApi(api: String): List<HistoryItemD> = rows.filter { it.api == api }

    override suspend fun updateHistory(historyItemD: HistoryItemD) {
        val idx = rows.indexOfFirst { it.id == historyItemD.id }
        if (idx >= 0) rows[idx] = historyItemD
        updates += historyItemD
    }

    // --- unused surface ---------------------------------------------------------------------------
    override fun getAllHistory(): Flow<List<HistoryItemD>> = flowOf(rows.toList())

    override suspend fun updateMangaImageUrl(
        mangaId: Long,
        newImageUrl: String,
    ) = Unit

    override suspend fun updateMangaImageUrlByUrl(
        mangaUrl: String,
        newImageUrl: String,
    ) = Unit

    override suspend fun getHistoryItemByMangaUrl(mangaUrl: String) = rows.firstOrNull { it.mangaUrl == mangaUrl }

    override suspend fun insertHistory(historyItemD: HistoryItemD) {
        rows += historyItemD
    }

    override suspend fun deleteHistory(historyItemD: HistoryItemD) {
        rows.removeAll { it.id == historyItemD.id }
    }

    override suspend fun deleteAllHistory() {
        rows.clear()
    }

    override suspend fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String>,
        lastReadDate: LocalDateTime,
        lastReadPage: Int,
        totalPages: Int,
    ) = Unit
}

internal class StatefulNotificationDao(
    seed: List<ChapterNotification> = emptyList(),
) : NotificationDao {
    val rows = seed.toMutableList()
    val updates = mutableListOf<ChapterNotification>()

    override suspend fun getNotificationsByApi(api: String): List<ChapterNotification> = rows.filter { it.api == api }

    override suspend fun updateNotification(notification: ChapterNotification) {
        val idx = rows.indexOfFirst { it.id == notification.id }
        if (idx >= 0) rows[idx] = notification
        updates += notification
    }

    // --- unused surface ---------------------------------------------------------------------------
    override suspend fun updateMangaImageUrl(
        mangaId: Long,
        newImageUrl: String,
    ) = Unit

    override suspend fun insertNotificationsList(notifications: List<ChapterNotification>): List<Long> {
        rows += notifications
        return notifications.map { it.id }
    }

    override fun getAllNotifications(): Flow<List<ChapterNotification>> = flowOf(rows.toList())

    override suspend fun markAllAsRead() = Unit

    override suspend fun deleteNotification(notification: ChapterNotification) {
        rows.removeAll { it.id == notification.id }
    }

    override suspend fun deleteAllNotifications() {
        rows.clear()
    }

    override suspend fun getNotificationByChapterId(chapterId: Long) = rows.firstOrNull { it.chapterId == chapterId }
}

internal class StatefulSourcesDao(
    seed: List<SourcesEntity> = emptyList(),
) : SourcesDao {
    private val flow = MutableStateFlow(seed.toList())
    val inserts = mutableListOf<SourcesEntity>()
    val baseUrlUpdates = mutableListOf<Triple<String, String, Int>>()
    val imageBaseUpdates = mutableListOf<Triple<String, String, Int>>()
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()
    val siteStateUpdates = mutableListOf<Pair<String, SourceState>>()
    val deletes = mutableListOf<String>()

    fun current(): List<SourcesEntity> = flow.value

    override fun getAllSources(): Flow<List<SourcesEntity>> = flow

    override suspend fun insert(source: SourcesEntity): Long {
        inserts += source
        flow.value = flow.value.filterNot { it.name == source.name } + source
        return 1L
    }

    override suspend fun updateBaseUrlAndVersionByName(
        name: String,
        baseUrl: String,
        version: Int,
    ): Int {
        baseUrlUpdates += Triple(name, baseUrl, version)
        flow.value = flow.value.map { if (it.name == name) it.copy(baseUrl = baseUrl, baseVersion = version) else it }
        return 1
    }

    override suspend fun updateImageBaseUrlAndVersionByName(
        apiName: String,
        newImageBaseUrl: String,
        newImageVersion: Int,
    ): Int {
        imageBaseUpdates += Triple(apiName, newImageBaseUrl, newImageVersion)
        flow.value =
            flow.value.map {
                if (it.name == apiName) {
                    it.copy(imageBaseUrl = newImageBaseUrl, imageUrlVersion = newImageVersion)
                } else {
                    it
                }
            }
        return 1
    }

    override suspend fun setEnabledByName(
        name: String,
        enabled: Boolean,
    ): Int {
        enabledCalls += name to enabled
        val before = flow.value
        flow.value = before.map { if (it.name == name) it.copy(isEnabled = enabled) else it }
        return if (before.any { it.name == name }) 1 else 0
    }

    // Stateful + recording since the SourceRegistry retirement (previously no-ops): the catalog
    // sync's lifecycle tests assert BOTH that these fire (siteState projection, lifecycle
    // "removed") and that steady state stays write-free.
    override suspend fun updateSiteStateByName(
        name: String,
        siteState: SourceState,
    ): Int {
        siteStateUpdates += name to siteState
        val before = flow.value
        flow.value = before.map { if (it.name == name) it.copy(siteState = siteState) else it }
        return if (before.any { it.name == name }) 1 else 0
    }

    override suspend fun deleteSourceByName(name: String): Int {
        deletes += name
        val before = flow.value
        flow.value = before.filterNot { it.name == name }
        return if (before.any { it.name == name }) 1 else 0
    }

    // --- unused surface ---------------------------------------------------------------------------
    override suspend fun getBaseUrlFor(name: String): String? = flow.value.firstOrNull { it.name == name }?.baseUrl

    override fun getSiteStateByName(name: String) = flowOf(flow.value.firstOrNull { it.name == name }?.siteState)

    override suspend fun getSiteStateByNameSync(name: String) = flow.value.firstOrNull { it.name == name }?.siteState
}

internal class FixedUpdateManager(
    private val doc: SourceConfigDocument,
) : SourceUpdateManager {
    override val state: StateFlow<UpdateState> =
        MutableStateFlow(UpdateState.Active(doc.revision, UpdateState.Origin.BUNDLED))

    override fun activeDocument(): SourceConfigDocument = doc

    override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(doc)
}

internal class PilotRegistry(
    private val piloted: Set<String>,
    private val descriptors: Map<String, RuntimeSourceDescriptor> = emptyMap(),
    private val client: (String) -> MangaSourceClient? = { null },
) : SourceRegistry {
    override fun get(api: String): MangaSourceClient? = if (api in piloted) client(api) else null

    override fun isConfigBacked(api: String): Boolean = api in piloted

    override fun descriptor(api: String): RuntimeSourceDescriptor? = descriptors[api] ?: if (api in piloted) fakeDescriptor(api) else null

    override fun genericDescriptors(): List<RuntimeSourceDescriptor> = piloted.map { descriptors[it] ?: fakeDescriptor(it) }
}

/** Descriptor a fake registry synthesizes for a piloted api — mirrors `toRuntimeDescriptor` defaults. */
internal fun fakeDescriptor(
    api: String,
    language: String = "(AR)",
): RuntimeSourceDescriptor =
    RuntimeSourceDescriptor(
        api = api,
        displayName = api,
        language = language,
        engine = "generic",
        baseUrl = "https://$api.test",
        priority = 0,
        enabledByDefault = false,
        siteState = "WORKING",
        lifecycle = "active",
        iconResourceKey = null,
        iconRemoteUrl = null,
        blacklistGenres = emptyList(),
    )
