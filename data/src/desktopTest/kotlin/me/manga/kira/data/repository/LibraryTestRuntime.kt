package me.manga.kira.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.repository.library.LibraryChapterWriter
import me.manga.kira.data.repository.library.LibraryMetadataWriter
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.LibraryRemovalStorage
import me.manga.kira.data.repository.library.LibraryRemovalWriter
import me.manga.kira.data.repository.library.LibraryWriteDependencies
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem

/** Shares the fixture's real Room/artifact owners. Catalog and removal permission are test-controlled. */
internal class LibraryTestRuntime(
    db: MangaDatabase,
    files: AppFileSystem,
    artifacts: ChapterArtifacts,
    mangaDao: MangaDao = db.mangaDao(),
    boundary: MangaWriteTransaction = RoomMangaWriteTransaction(db),
) {
    val transactions = LibraryObservedTransactions(boundary)
    val snapshots = LibraryControlledSnapshots(transactions, libraryPolicy())
    val owners = LibraryOwnerTransactions(transactions, snapshots, mangaDao)
    val guard = LibraryControlledGuard()
    private val metadata = LibraryMetadataWriter(mangaDao, db.libraryDeo())
    private val storage = ProgressStorage(
        db.readerProgressDao(), db.readerLegacyCleanupDao(), mangaDao, db.chapterDao(), db.backupDao(),
    )
    private val writes = LibraryWriteDependencies(
        owners,
        db.libraryDeo(),
        metadata,
        LibraryChapterWriter(db.libraryDeo()),
        LibraryRemovalWriter(
            owners, LibraryRemovalStorage(db.libraryDeo(), storage, db.chapterDownloadingDao()),
            guard, FileService(files), artifacts,
        ),
    )
    val repository = LibraryRepositoryImpl(mangaDao, writes, LibraryJoinedTestDispatchers)
    val covers = LibraryMetadataRepositoryImpl(owners, metadata, db.libraryDeo(), LibraryJoinedTestDispatchers)
    val savedDetails = SavedMangaDetailsRepositoryImpl(owners, db.chapterDao(), LibraryJoinedTestDispatchers)
}

/** Aligns the first two attempts outside Room, then delegates every write to the actual database. */
internal class LibraryConcurrentWriteBarrier(private val delegate: MangaWriteTransaction) : MangaWriteTransaction {
    private val attempts = AtomicInteger()
    private val bothAttempted = CompletableDeferred<Unit>()

    override suspend fun <T> write(block: suspend () -> T): T {
        if (attempts.incrementAndGet() == 2) bothAttempted.complete(Unit)
        bothAttempted.await()
        return delegate.write(block)
    }
}

private object LibraryJoinedTestDispatchers : DispatcherProvider {
    override val main = Dispatchers.Default
    override val mainImmediate = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.IO
    override val unconfined = Dispatchers.Unconfined
}
