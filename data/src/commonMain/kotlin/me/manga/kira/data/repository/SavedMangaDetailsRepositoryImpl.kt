package me.manga.kira.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.mapper.toDomainDetails
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.SavedMangaDetailsRepository

/**
 * Room-backed [SavedMangaDetailsRepository]: the offline/local Details projection.
 *
 * SRP (contract §6): owns ONE rule — "resolve a saved manga by `(api, title)` and emit its saved
 * details (manga row + reactive chapter list with persisted read/downloaded/bookmark state), or
 * `null` when it isn't in the library". Source routing / network fetch stays in
 * [MangaDetailsRepositoryImpl]; this impl never touches the network.
 *
 * Strangler-fig boundary: depends directly on the `:shared` Room DAOs ([MangaDao], [ChapterDao]) —
 * the same cell-of-truth other rework `:data` impls inject (e.g. DownloadsActionRepositoryImpl,
 * ChapterIdResolverImpl) — rather than the heavyweight legacy `LibraryRepository` facade. Mapping
 * lives in `:data`'s [toDomainDetails] mapper.
 *
 * Threading: the DAO reads run on [DispatcherProvider.io] via [flowOn]; the returned [Flow] is cold
 * and membership-reactive — it observes the saved-manga table and (re)attaches the reactive chapter
 * flow whenever the `(api, title)` row appears or disappears, so a Details screen opened on a
 * non-library manga that the user later adds starts emitting saved details without re-collection.
 */
class SavedMangaDetailsRepositoryImpl(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val dispatchers: DispatcherProvider,
) : SavedMangaDetailsRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSavedDetails(api: String, title: String): Flow<MangaDetails?> =
        mangaDao.getAllSavedMangaFlow()
            .map { list -> list.firstOrNull { it.api == api && it.title == title } }
            .distinctUntilChanged()
            .flatMapLatest { manga ->
                if (manga == null) {
                    flowOf(null)
                } else {
                    chapterDao.getChaptersByMangaId(manga.id).map { chapters ->
                        manga.toDomainDetails(chapters)
                    }
                }
            }
            .flowOn(dispatchers.io)
}
