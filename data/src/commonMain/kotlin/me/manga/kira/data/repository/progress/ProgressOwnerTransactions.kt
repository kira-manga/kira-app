package me.manga.kira.data.repository.progress

import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.local.MangaWriteTransaction

/**
 * Required accepted-selection authority is read AFTER acquiring the Room writer, never cached here.
 * Composition obtains readiness outside this writer. Provider failure propagates, even for exact
 * anchors; an empty committed policy is not permission to substitute a not-ready/default provider.
 */
class ProgressOwnerTransactions(
    private val transactions: MangaWriteTransaction,
    private val snapshots: SourceAliasSnapshotProvider,
    private val storage: ProgressStorage,
) {
    internal suspend fun <T> write(block: suspend ProgressOwnerSession.() -> T): T =
        transactions.write {
            val policy = WorkAliasPolicy(snapshots.readInTransaction())
            ProgressOwnerSession(storage, policy).block()
        }
}
