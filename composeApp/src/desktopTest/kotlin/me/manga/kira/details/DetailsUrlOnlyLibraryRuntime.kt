package me.manga.kira.details

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaDatabase
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
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SourceSelectionToken

/** Explicit test policy observed only inside the actual writer; never a production selection root. */
internal class DetailsUrlOnlyLibraryRuntime(
    db: MangaDatabase,
    files: AppFileSystem,
    artifacts: ChapterArtifacts,
    dispatchers: DispatcherProvider,
) {
    private var insideWriter = false
    private val actual = RoomMangaWriteTransaction(db)
    private val writer =
        object : MangaWriteTransaction {
            override suspend fun <T> write(block: suspend () -> T): T =
                actual.write {
                    check(!insideWriter)
                    insideWriter = true
                    try {
                        block()
                    } finally {
                        insideWriter = false
                    }
                }
        }
    private val policy =
        SourceAliasSnapshot(
            SourceSelectionToken(
                1L,
                SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, 1L, "a".repeat(64)),
                "b".repeat(64),
            ),
            listOf(AcceptedSourceAliasRule("source", "https://details.test", emptyList())),
        )
    private val snapshots =
        object : SourceAliasSnapshotProvider {
            override val readiness = MutableStateFlow<SourceAliasReadiness>(SourceAliasReadiness.Ready(policy.token))

            override suspend fun readInTransaction(): SourceAliasSnapshot {
                check(insideWriter) { "The finite Details test policy requires the actual Room writer" }
                return policy
            }
        }
    val owners = LibraryOwnerTransactions(writer, snapshots, db.mangaDao())
    private val progress =
        ProgressStorage(
            db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao(),
        )
    private val writes =
        LibraryWriteDependencies(
            owners,
            db.libraryDeo(),
            LibraryMetadataWriter(db.mangaDao(), db.libraryDeo()),
            LibraryChapterWriter(db.libraryDeo()),
            LibraryRemovalWriter(
                owners,
                LibraryRemovalStorage(db.libraryDeo(), progress, db.chapterDownloadingDao()),
                UnsupportedDetailsUrlOnlyRemoval,
                FileService(files),
                artifacts,
            ),
        )
    val repository = LibraryRepositoryImpl(db.mangaDao(), writes, dispatchers)
}

/** This no-engine fixture grants no removal lease and cannot stand in for a stop/join adapter. */
private object UnsupportedDetailsUrlOnlyRemoval : LibraryRemovalGuard {
    override suspend fun <T> withQuiescentWorks(owners: List<SavedWorkIdentity>, block: suspend () -> T): T =
        error("Library removal is outside the Details URL-only fixture")

    override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>): Unit =
        error("No removal authority was granted by the Details URL-only fixture")
}

internal class DetailsUrlOnlyForeignKeysDriver(private val actual: SQLiteDriver) : SQLiteDriver by actual {
    override fun open(fileName: String): SQLiteConnection =
        actual.open(fileName).also { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.prepare("PRAGMA foreign_keys").use { statement ->
                check(statement.step() && statement.getLong(0) == 1L)
            }
        }
}
