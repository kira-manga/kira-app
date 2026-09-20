package me.manga.kira.data.repository

import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.repository.library.LibraryMetadataWriter
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.libraryStorageResult
import me.manga.kira.data.repository.library.relatedRows
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.repository.LibraryMetadataRepository

/** Cover-only bridge shared by the legacy facade; has no download-engine or legacy-repository cycle. */
class LibraryMetadataRepositoryImpl(
    private val owners: LibraryOwnerTransactions,
    private val writer: LibraryMetadataWriter,
    private val libraryDao: LibraryDeo,
    private val dispatchers: DispatcherProvider,
) : LibraryMetadataRepository {
    override suspend fun updateCoverIfChanged(
        owner: SavedWorkIdentity,
        fetched: WorkLocator,
        newCoverUrl: String,
    ): AppResult<Unit> = libraryStorageResult {
        withContext(dispatchers.io) {
            owners.write {
                val current = retain(owner)
                requireSameWork(current.savedIdentity().locator, fetched)
                requireOwner(current, resolve(fetched))
                writer.cover(current, newCoverUrl, relatedRows(current, libraryDao))
            }
        }
    }
}
