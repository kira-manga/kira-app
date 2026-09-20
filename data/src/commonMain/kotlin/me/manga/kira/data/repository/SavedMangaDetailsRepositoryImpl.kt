package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.mapper.toDomainDetails
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.libraryStorageFailures
import me.manga.kira.data.repository.library.libraryStorageResult
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.SavedMangaDetailsRepository

/** Cold local projection; never pairs a parent from one transaction with another owner's chapters. */
class SavedMangaDetailsRepositoryImpl(
    private val owners: LibraryOwnerTransactions,
    private val chapterDao: ChapterDao,
    private val dispatchers: DispatcherProvider,
) : SavedMangaDetailsRepository {
    override fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> =
        owners.invalidations().map {
            libraryStorageResult {
                owners.write {
                    val parent = resolve(work) ?: return@write null
                    // Discovery stores oldest first; readers and Details consume source (newest-first) order.
                    val chapters = chapterDao.getChaptersByMangaIdR(parent.id).sortedByDescending { it.id }
                    SavedWorkDetails(parent.savedIdentity(), parent.toDomainDetails(chapters))
                }
            }
        }.libraryStorageFailures().distinctUntilChanged().flowOn(dispatchers.io)
}
