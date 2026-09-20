package me.manga.kira.data.repository

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.mapper.toLibraryManga
import me.manga.kira.data.mapper.toNewLibraryEntity
import me.manga.kira.data.repository.library.LibraryOwnerSession
import me.manga.kira.data.repository.library.LibraryRefreshPlan
import me.manga.kira.data.repository.library.LibraryWriteDependencies
import me.manga.kira.data.repository.library.LibraryWriteRejection
import me.manga.kira.data.repository.library.libraryStorageFailures
import me.manga.kira.data.repository.library.libraryStorageResult
import me.manga.kira.data.repository.library.relatedRows
import me.manga.kira.data.repository.library.requireLibraryWrite
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Room library aggregate. Every ownership read/mutation resolves a complete exact/accepted-alias
 * family under its writer's accepted snapshot; title/language are metadata only.
 * The historical audit below describes the pre-identity implementation, not current semantics.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster23.staleKdocSweep.cascade,
 * Task #479, 2026-05-28): one fulfilled-forecast citation appears
 * inside the method-level `toggleLiked` KDoc below:
 *  - The `toggleLiked` KDoc closes with "Phase 9.x retires the legacy
 *    DAO reach". HALF-FULFILLED — Phase 8.y.library.swap (§346)
 *    re-pointed `Screen.Library`'s rendering adapter to the rework
 *    UI; Phase 9.x.library.retire (§347) deleted the orphan legacy
 *    `:shared` Library screen; Phase 9.x.deadcomposable.retire
 *    (§348) pruned the legacy LibraryViewModel's dead composables.
 *    HOWEVER — the legacy [MangaDao] + [LibraryDeo] Room DAOs STILL
 *    EXIST as the cell of truth that this impl delegates to via
 *    `mangaDao: MangaDao` + `libraryDeo: LibraryDeo` constructor
 *    params (verified at the constructor signature below). The
 *    "Phase 9.x retires the legacy DAO reach" forecast was
 *    HALF-FULFILLED at the route-swap layer (§346) + UI-retire layer
 *    (§347 + §348); the DAO-reach itself remains as the rework's
 *    Room transport backbone. Mirror of §477 library cluster +
 *    §475-478 partially-fulfilled-inversion precedent.
 * The class-level SRP / DIP / membership-semantics sub-section + the
 * `toggleLiked` / `toggleWatchingNow` strangler-fig method KDocs +
 * the `toNewEntity` projection rule all stand on their own merits
 * past the §§346 + 347 + 348 fulfilled landings. The
 * LibraryRepositoryImpl remains LIVE as the canonical Room-backed
 * implementation for the rework library surface. Original §253-era
 * prose preserved verbatim per the audit-trail-preservation
 * convention — the citation is historical record of the design
 * lineage including the deferred-DAO-reach-retire forecast that was
 * subsequently half-fulfilled across §§346 + 347 + 348 (UI retired,
 * DAO transport retained).
 */
@OptIn(ExperimentalTime::class)
class LibraryRepositoryImpl(
    private val mangaDao: MangaDao,
    private val writes: LibraryWriteDependencies,
    private val dispatchers: DispatcherProvider,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryManga>> =
        combine(
            mangaDao.getAllSavedMangaFlow().distinctUntilChanged(),
            mangaDao.getAllChapterMetricsFlow().distinctUntilChanged(),
        ) { mangas, metrics ->
            val byId = metrics.associateBy { it.mangaId }
            mangas.map { entity ->
                val counts = byId[entity.id]
                entity.toLibraryManga(
                    totalChapters = counts?.totalChapters ?: 0,
                    readCount = counts?.readCount ?: 0,
                    downloadedCount = counts?.downloadedCount ?: 0,
                    lastReadTs = counts?.lastReadTs,
                    bookmarkedCount = counts?.bookmarkedCount ?: 0,
                )
            }
        }.distinctUntilChanged().flowOn(dispatchers.io)

    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> =
        writes.owners.invalidations()
            .map { libraryStorageResult { writes.owners.write { resolve(work)?.savedIdentity() } } }
            .libraryStorageFailures()
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override suspend fun get(work: WorkLocator): AppResult<LibraryManga?> = libraryStorageResult {
        withContext(dispatchers.io) {
            writes.owners.write {
                resolve(work)?.toLibraryManga(0, 0, 0, null, 0)
            }
        }
    }

    override suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity> =
        libraryStorageResult {
            withContext(dispatchers.io) {
                writes.owners.write {
                    val current = prepareAdd(fetched) ?: insertParent(fetched)
                    val plan = preparePlan(current, fetched)
                    commit(plan, refreshing = false, notify = false).owner
                }
            }
        }

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> = libraryStorageResult {
        withContext(dispatchers.io) {
            writes.owners.write {
                val plans = requests.map { preparePlan(acceptFetched(it.owner, it.fetched), it.fetched) }
                val ids = plans.map { it.owner.id }
                requireLibraryWrite(ids.distinct().size == ids.size, LibraryWriteRejection.DUPLICATE_REQUEST)
                plans.map { commit(it, refreshing = true, notify = notify) }
            }
        }
    }

    override suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit> = libraryStorageResult {
        withContext(dispatchers.io) {
            writes.removal.remove(listOf(owner))
            Unit
        }
    }

    override suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int> =
        libraryStorageResult { withContext(dispatchers.io) { writes.removal.remove(owners) } }

    override suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit> = updateOwner(owner) {
        mangaDao.toggleLikedForExactOwner(it.id, it.api, it.url)
    }

    override suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit> = updateOwner(owner) {
        mangaDao.toggleWatchingForExactOwner(it.id, it.api, it.url)
    }

    override suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit> = updateOwner(owner) {
        mangaDao.updateOpenedForExactOwner(it.id, it.api, it.url, Clock.System.now().toEpochMilliseconds())
    }

    private suspend fun updateOwner(
        owner: SavedWorkIdentity,
        update: suspend (SavedMangaEntity) -> Int,
    ): AppResult<Unit> = libraryStorageResult {
        withContext(dispatchers.io) {
            writes.owners.write {
                requireLibraryWrite(update(retain(owner)) == 1, LibraryWriteRejection.WRITE_COUNT)
            }
        }
    }

    private suspend fun LibraryOwnerSession.insertParent(fetched: FetchedWorkDetails): SavedMangaEntity {
        val id = writes.libraryDao.insertManga(fetched.toNewLibraryEntity(Clock.System.now().toEpochMilliseconds()))
        requireLibraryWrite(id > 0, LibraryWriteRejection.INSERT_CONFLICT)
        return retain(SavedWorkIdentity(id, fetched.requested))
    }

    private suspend fun LibraryOwnerSession.preparePlan(
        owner: SavedMangaEntity,
        fetched: FetchedWorkDetails,
    ) = LibraryRefreshPlan(
        owner = owner,
        fetched = fetched,
        newChapters = writes.chapters.missing(this, owner, fetched.details.chapters),
        related = relatedRows(owner, writes.libraryDao),
    )

    private suspend fun commit(plan: LibraryRefreshPlan, refreshing: Boolean, notify: Boolean): LibraryRefreshReceipt {
        val current = writes.metadata.details(plan.owner, plan.fetched.details, plan.related)
        return writes.chapters.insert(current, plan.newChapters, refreshing, notify)
    }
}
