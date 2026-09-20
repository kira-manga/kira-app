package me.manga.kira.data.repository.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.dao.MangaDao

/**
 * All library ownership decisions enter the writer before acquiring accepted alias policy.
 * Composition must supply the real transaction-consistent provider, never a cached/default policy.
 */
class LibraryOwnerTransactions(
    private val transactions: MangaWriteTransaction,
    private val snapshots: SourceAliasSnapshotProvider,
    private val mangaDao: MangaDao,
) {
    internal suspend fun <T> write(block: suspend LibraryOwnerSession.() -> T): T =
        transactions.write {
            val snapshot = snapshots.readInTransaction()
            LibraryOwnerSession(mangaDao, snapshot).block()
        }

    internal fun invalidations(): Flow<Unit> =
        combine(
            mangaDao.getAllSavedMangaFlow(),
            mangaDao.getAllChapterMetricsFlow(),
            snapshots.readiness,
        ) { _, _, _ -> Unit }
}
