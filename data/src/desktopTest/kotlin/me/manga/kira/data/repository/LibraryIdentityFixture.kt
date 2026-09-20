package me.manga.kira.data.repository

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.Closeable
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.MangaWriteTransaction
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
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
import me.manga.kira.platform.filesystem.mangaDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Generated Room DAOs + real writer/rollback. Only catalog acceptance and engine lease are controlled. */
internal class LibraryIdentityFixture : Closeable {
    val db = Room.inMemoryDatabaseBuilder<MangaDatabase>()
        .addCallback(ReaderProgressConstraints)
        .setDriver(LibraryForeignKeysDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    val transactions = LibraryObservedTransactions(RoomMangaWriteTransaction(db))
    val snapshots = LibraryControlledSnapshots(transactions, libraryPolicy())
    val guard = LibraryControlledGuard()
    val files = LibraryIdentityFiles()
    val artifacts = ChapterArtifacts(
        db.chapterArtifactDao(),
        ChapterArtifactRecovery(db.chapterArtifactDao(), db.chapterArtifactCommitDao(), files, DesktopPageMediaInspector()),
    )
    val owners = LibraryOwnerTransactions(transactions, snapshots, db.mangaDao())
    val metadata = LibraryMetadataWriter(db.mangaDao(), db.libraryDeo())
    val repository = LibraryRepositoryImpl(db.mangaDao(), dependencies(), LibraryTestDispatchers)
    val covers = LibraryMetadataRepositoryImpl(owners, metadata, db.libraryDeo(), LibraryTestDispatchers)
    val savedDetails = SavedMangaDetailsRepositoryImpl(owners, db.chapterDao(), LibraryTestDispatchers)

    private fun dependencies() = LibraryWriteDependencies(
        owners, db.libraryDeo(), metadata, LibraryChapterWriter(db.libraryDeo()),
        LibraryRemovalWriter(
            owners,
            LibraryRemovalStorage(db.libraryDeo(),
                ProgressStorage(db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao()),
                db.chapterDownloadingDao()),
            guard, FileService(files), artifacts,
        ),
    )

    suspend fun parent(draft: SavedMangaEntity = libraryParent()): SavedMangaEntity {
        val id = db.backupDao().insertMangaRow(draft)
        check(id > 0)
        return draft.copy(id = id)
    }

    suspend fun chapter(draft: SavedChapterEntity): SavedChapterEntity {
        val id = db.backupDao().insertChapterRow(draft)
        check(id > 0)
        return draft.copy(id = id)
    }

    suspend fun execute(sql: String) {
        db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
    }

    override fun close() {
        db.close()
        files.close()
    }
}

/** Observes, but does not replace or simulate, the real transaction boundary. */
internal class LibraryObservedTransactions(private val delegate: MangaWriteTransaction) : MangaWriteTransaction {
    var insideWriter = false
        private set
    var onAttempt: () -> Unit = {}
    var beforeCommit: suspend () -> Unit = {}

    override suspend fun <T> write(block: suspend () -> T): T {
        onAttempt()
        return delegate.write {
            check(!insideWriter)
            insideWriter = true
            try { block().also { beforeCommit() } } finally { insideWriter = false }
        }
    }
}

/** Not a production accepted-token provider. Test policy changes are explicit and transaction reads recorded. */
internal class LibraryControlledSnapshots(
    private val writer: LibraryObservedTransactions,
    initial: SourceAliasSnapshot,
) : SourceAliasSnapshotProvider {
    private var snapshot = initial
    override val readiness = MutableStateFlow<SourceAliasReadiness>(SourceAliasReadiness.Ready(initial.token))
    var readCount = 0
        private set

    fun accept(value: SourceAliasSnapshot) {
        snapshot = value
        readiness.value = SourceAliasReadiness.Ready(value.token)
    }

    fun invalidate() { readiness.value = SourceAliasReadiness.NotReady }

    override suspend fun readInTransaction(): SourceAliasSnapshot {
        check(writer.insideWriter) { "Policy read before owning writer" }
        readCount++
        if (readiness.value == SourceAliasReadiness.NotReady) throw SourceSelectionUnavailable("controlled not-ready")
        return snapshot
    }
}

/** Test fixture has no engine workers; a test must explicitly grant the specific owner IDs. */
internal class LibraryControlledGuard : LibraryRemovalGuard {
    private var grantedIds = emptySet<Long>()
    var held = false
        private set
    var checks = 0
        private set
    var onCheck: suspend (List<SavedWorkIdentity>) -> Unit = {}

    fun grant(owners: List<SavedWorkIdentity>) { grantedIds = owners.map { it.id }.toSet() }

    override suspend fun <T> withQuiescentWorks(owners: List<SavedWorkIdentity>, block: suspend () -> T): T {
        check(owners.all { it.id in grantedIds }) { "No test engine lease was granted" }
        check(!held)
        held = true
        return try { block() } finally { held = false }
    }

    override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>) {
        check(held && owners.all { it.id in grantedIds })
        checks++
        onCheck(owners)
    }
}

internal class LibraryIdentityFiles : AppFileSystem, Closeable {
    private val root = Files.createTempDirectory("kira-library-identity-").toString().toPath()
    override val filesDir = root / "files"
    override val cacheDir = root / "cache"
    var beforeDelete: (Path) -> Unit = {}
    private val checkedFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun delete(path: Path, mustExist: Boolean) {
            beforeDelete(path)
            super.delete(path, mustExist)
        }
    }

    override fun fileSystem(): FileSystem = checkedFiles

    fun seed(owner: SavedWorkIdentity) {
        FileSystem.SYSTEM.createDirectories(mangaDir(owner.id))
        FileSystem.SYSTEM.write(mangaDir(owner.id) / "keep.cbz") { writeUtf8("fixture") }
    }

    fun exists(owner: SavedWorkIdentity): Boolean = FileSystem.SYSTEM.exists(mangaDir(owner.id) / "keep.cbz")
    override fun close() = FileSystem.SYSTEM.deleteRecursively(root)
}

private class LibraryForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName).also { connection ->
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.prepare("PRAGMA foreign_keys").use { statement ->
            check(statement.step() && statement.getLong(0) == 1L)
        }
    }
}

private object LibraryTestDispatchers : DispatcherProvider {
    override val main = Dispatchers.Default
    override val mainImmediate = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.Default
    override val unconfined = Dispatchers.Unconfined
}
