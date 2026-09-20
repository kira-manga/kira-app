package me.manga.kira.data.repository.library

import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.WorkAliasComparison
import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.identity.WorkOwnerResolver
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.repository.progress.ProgressOwnerSession
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails

/** Valid only within LibraryOwnerTransactions.write; never retain this across a suspension outside it. */
internal class LibraryOwnerSession(
    private val mangaDao: MangaDao,
    snapshot: SourceAliasSnapshot,
) {
    private val policy = WorkAliasPolicy(snapshot)
    private val resolver = WorkOwnerResolver(policy)

    suspend fun resolve(work: WorkLocator): SavedMangaEntity? = resolve(work, null)

    suspend fun retain(owner: SavedWorkIdentity): SavedMangaEntity =
        checkNotNull(resolve(owner.locator, owner))

    /** Reuses this writer and policy so alias moves, generation clear and deletion commit together. */
    suspend fun clearWorkProgress(storage: ProgressStorage, work: WorkLocator) {
        ProgressOwnerSession(storage, policy).clearWork(work)
    }

    /** Read-only refusal before irreversible artifact cleanup; the final writer resolves again. */
    suspend fun requireWorkProgressOwner(storage: ProgressStorage, work: WorkLocator) {
        ProgressOwnerSession(storage, policy).resolveWork(work)
    }

    suspend fun acceptFetched(owner: SavedWorkIdentity, fetched: FetchedWorkDetails): SavedMangaEntity {
        val current = retain(owner)
        requireSameWork(fetched.requested, fetched.details.locator())
        requireOwner(current, resolve(fetched.requested))
        requireOwner(current, resolve(fetched.details.locator()))
        return current
    }

    suspend fun prepareAdd(fetched: FetchedWorkDetails): SavedMangaEntity? {
        requireSameWork(fetched.requested, fetched.details.locator())
        val requested = resolve(fetched.requested)
        val returned = resolve(fetched.details.locator())
        requireLibraryWrite(requested?.id == returned?.id, LibraryWriteRejection.FETCHED_WORK_CHANGED)
        return requested
    }

    fun compare(first: WorkLocator, second: WorkLocator): WorkAliasComparison = policy.compare(first, second)

    fun requireSameWork(first: WorkLocator, second: WorkLocator) {
        val comparison = compare(first, second)
        requireLibraryWrite(
            comparison == WorkAliasComparison.Exact || comparison == WorkAliasComparison.DeclaredAlias,
            LibraryWriteRejection.FETCHED_WORK_CHANGED,
        )
    }

    fun requireOwner(expected: SavedMangaEntity, actual: SavedMangaEntity?) {
        requireLibraryWrite(expected.id == actual?.id, LibraryWriteRejection.FETCHED_WORK_CHANGED)
    }

    private suspend fun resolve(work: WorkLocator, retained: SavedWorkIdentity?): SavedMangaEntity? {
        val exact = mangaDao.getMangaByExactUrl(work.url)?.savedIdentity()
        val candidates = mangaDao.getMangaByApi(work.api)
        val identities = candidates.map { it.savedIdentity() }
        val resolved = if (retained == null) {
            resolver.resolve(work, exact, identities)
        } else {
            resolver.resolveRetained(retained, exact, identities)
        }
        return when (resolved) {
            is WorkOwnerResolution.Missing -> null
            is WorkOwnerResolution.Conflict ->
                throw LibraryWriteException(LibraryWriteRejection.OWNER_CONFLICT, resolved)
            is WorkOwnerResolution.Found -> candidates.single { it.savedIdentity() == resolved.owner }
        }
    }
}

private fun me.manga.kira.domain.model.MangaDetails.locator() = WorkLocator(api, url)
