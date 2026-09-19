package me.manga.kira.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository

/** Delegation fake only; exact test addresses are explicit and alias/transaction policy is not simulated. */
class FakeLibraryRepository : LibraryRepository {
    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val membership = MutableStateFlow<Map<WorkLocator, AppResult<SavedWorkIdentity?>>>(emptyMap())
    val calls = mutableListOf<String>()
    var lastAddedDetails: MangaDetails? = null
        private set
    var lastAddedFetched: FetchedWorkDetails? = null
        private set
    var lastRemovedOwner: SavedWorkIdentity? = null
        private set
    var lastBulkOwners: List<SavedWorkIdentity> = emptyList()
        private set
    var lastRefreshRequests: List<LibraryRefreshRequest> = emptyList()
        private set
    var removeResult: AppResult<Unit> = AppResult.Success(Unit)
    var removeAllResult: AppResult<Int>? = null
    var refreshFailure: AppError? = null
    var persistedCount: Int = 0

    fun emitLibrary(value: List<LibraryManga>) { library.value = value }
    fun emitMembership(work: WorkLocator, result: AppResult<SavedWorkIdentity?>) {
        membership.value = membership.value + (work to result)
    }

    override fun observeLibrary(): Flow<List<LibraryManga>> = library.asStateFlow()
    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> =
        combine(library, membership) { rows, overrides ->
            overrides[work] ?: AppResult.Success(rows.singleOrNull { it.identity.locator == work }?.identity)
        }

    override suspend fun get(work: WorkLocator): AppResult<LibraryManga?> {
        calls += "get(${work.api},${work.url})"
        return AppResult.Success(library.value.singleOrNull { it.identity.locator == work })
    }

    override suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity> {
        calls += "addToLibrary(${fetched.requested.api},${fetched.requested.url},chapters=${fetched.details.chapters.size})"
        lastAddedFetched = fetched
        lastAddedDetails = fetched.details
        return AppResult.Success(SavedWorkIdentity(1L, fetched.requested))
    }

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> {
        calls += "refresh(${requests.size},notify=$notify)"
        lastRefreshRequests = requests
        refreshFailure?.let { return AppResult.Failure(it) }
        return AppResult.Success(requests.map { LibraryRefreshReceipt(it.owner, persistedCount, emptyList()) })
    }

    override suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "removeFromLibrary(${owner.id},${owner.locator})"
        lastRemovedOwner = owner
        return removeResult
    }

    override suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int> {
        calls += "removeAllFromLibrary(${owners.size})"
        lastBulkOwners = owners
        return removeAllResult ?: AppResult.Success(owners.distinct().size)
    }

    override suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "toggleLiked(${owner.id})"
        return AppResult.Success(Unit)
    }

    override suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "toggleWatchingNow(${owner.id})"
        return AppResult.Success(Unit)
    }

    override suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "markOpened(${owner.id})"
        return AppResult.Success(Unit)
    }
}
