package me.manga.kira.presentation.features.download.ui.test2

import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.core.dispatchers.DefaultDispatcherProvider
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.repository.LibraryRepositoryImpl
import me.manga.kira.data.repository.library.LibraryChapterWriter
import me.manga.kira.data.repository.library.LibraryMetadataWriter
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.LibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryRemovalStorage
import me.manga.kira.data.repository.library.LibraryRemovalWriter
import me.manga.kira.data.repository.library.LibraryWriteDependencies
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.service.FileService
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SourceSelectionToken

/**
 * Lower-path regression composition only, NOT a production LibraryRemovalGuard or selection root.
 * The fixture explicitly owns one independently started real worker and a finite test alias policy.
 * It requests actual Android cancellation, joins that original worker, and only then acquires the
 * shared gate. Production removal still needs its own complete stop/join adapter before wiring.
 */
internal class AndroidLibraryDrainRegression(fixture: DownloadWorkerCancellationFixture) {
    private val db = fixture.rows.db
    val owner = SavedWorkIdentity(
        fixture.rows.manga.id, WorkLocator(fixture.rows.manga.api, fixture.rows.manga.url),
    )
    private val writer = AndroidRemovalObservedWriter(RoomMangaWriteTransaction(db))
    private val snapshots = AndroidRemovalControlledSnapshot(writer)
    private val owners = LibraryOwnerTransactions(writer, snapshots, db.mangaDao())
    private val guard = AndroidRemovalOriginalWorkerGuard(fixture, owner, writer)
    private val progress = ProgressStorage(
        db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao(),
    )
    private val writes = LibraryWriteDependencies(
        owners = owners,
        libraryDao = db.libraryDeo(),
        metadata = LibraryMetadataWriter(db.mangaDao(), db.libraryDeo()),
        chapters = LibraryChapterWriter(db.libraryDeo()),
        removal = LibraryRemovalWriter(
            owners,
            LibraryRemovalStorage(db.libraryDeo(), progress, db.chapterDownloadingDao()),
            guard,
            FileService(fixture.storage.fileSystem),
            fixture.rows.artifacts.ownership,
        ),
    )
    val repository = LibraryRepositoryImpl(db.mangaDao(), writes, DefaultDispatcherProvider())
    val guardChecks: Int get() = guard.checks
    val snapshotReads: Int get() = snapshots.reads
    val exclusiveHeld: Boolean get() = guard.held
}

/** Observes entry into the real Room writer; it never supplies a replacement transaction result. */
private class AndroidRemovalObservedWriter(private val actual: MangaWriteTransaction) : MangaWriteTransaction {
    var insideWriter = false
        private set

    override suspend fun <T> write(block: suspend () -> T): T = actual.write {
        check(!insideWriter)
        insideWriter = true
        try { block() } finally { insideWriter = false }
    }
}

/** Explicit test policy, not a default accepted snapshot or proof of durable runtime selection. */
private class AndroidRemovalControlledSnapshot(
    private val writer: AndroidRemovalObservedWriter,
) : SourceAliasSnapshotProvider {
    private val snapshot = SourceAliasSnapshot(
        SourceSelectionToken(
            1L, SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, 1L, "a".repeat(64)), "b".repeat(64),
        ),
        listOf(AcceptedSourceAliasRule(FIXTURE_API, "https://example.test", emptyList())),
    )
    override val readiness = MutableStateFlow<SourceAliasReadiness>(SourceAliasReadiness.Ready(snapshot.token))
    var reads = 0
        private set

    override suspend fun readInTransaction(): SourceAliasSnapshot {
        check(writer.insideWriter) { "Fixture alias read escaped its actual writer" }
        reads++
        return snapshot
    }
}

/** Not an engine-free grant: every successful invocation waits for both original physical Jobs. */
private class AndroidRemovalOriginalWorkerGuard(
    private val fixture: DownloadWorkerCancellationFixture,
    private val owner: SavedWorkIdentity,
    private val writer: AndroidRemovalObservedWriter,
) : LibraryRemovalGuard {
    var held = false
        private set
    var checks = 0
        private set

    override suspend fun <T> withQuiescentWorks(owners: List<SavedWorkIdentity>, block: suspend () -> T): T {
        check(owners == listOf(owner) && !held && !writer.insideWriter)
        checkNotNull(fixture.worker.job) { "The original fixture worker must already be captured" }
        checkNotNull(fixture.producer.job) { "The original fixture producer must already be captured" }
        fixture.androidRepository().cancelAllDownloads()
        // WorkManager's stop request alone cannot settle the independently started fixture worker.
        // A failed/cancelled join or busy exclusive never invokes the lower removal block.
        fixture.joinSuccessfulWorker()
        return fixture.rows.operations.withExclusive {
            check(!held)
            held = true
            try { block() } finally { held = false }
        }
    }

    override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>) {
        check(writer.insideWriter && held && owners == listOf(owner))
        check(checkNotNull(fixture.worker.job).isCompleted && checkNotNull(fixture.producer.job).isCompleted)
        val current = checkNotNull(fixture.rows.db.mangaDao().getMangaById(owner.id))
        check(SavedWorkIdentity(current.id, WorkLocator(current.api, current.url)) == owner)
        check(fixture.rows.realDao.getActiveDownloadChapterIdsForManga(owner.id).isEmpty())
        checks++
    }
}
