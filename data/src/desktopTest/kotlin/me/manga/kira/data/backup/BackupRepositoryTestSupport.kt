package me.manga.kira.data.backup

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.data.repository.LibraryControlledSnapshots
import me.manga.kira.data.repository.LibraryObservedTransactions
import me.manga.kira.data.repository.libraryPolicy
import me.manga.kira.data.repository.progress.LegacyProgressSettings
import me.manga.kira.data.repository.progress.LegacyProgressSettingsGate
import me.manga.kira.data.repository.progress.ProgressOwnerTransactions
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.data.repository.progress.ProgressTestSettings
import me.manga.kira.data.repository.progress.ProgressTestWriterOwnership
import me.manga.kira.data.repository.progress.RoomScopedReadProgressRepository
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.Path
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Existing end-to-end Room/filesystem fixture, shared with production admission assertions. */
internal fun backupTestRepository(
    db: MangaDatabase,
    files: AppFileSystem,
    mergeWriter: BackupMergeWriter,
    inspector: PageMediaInspector = DesktopPageMediaInspector(),
    policy: BackupImportPolicy = BackupImportPolicy(),
    operations: DownloadOperationExclusion = DownloadOperationExclusion(),
): BackupRepositoryImpl {
    val recovery = ChapterArtifactRecovery(db.chapterArtifactDao(), db.chapterArtifactCommitDao(), files,
        me.manga.kira.platform.media.DesktopPageMediaInspector())
    val artifacts = ChapterArtifacts(db.chapterArtifactDao(), recovery)
    val publisher = RestoredDownloadPublisher(artifacts, db.chapterArtifactDao(), db.chapterArtifactCommitDao(), files, recovery)
    val preflight = BackupArchivePreflight(files, BackupImportStaging(files, policy), inspector, policy)
    val downloads = BackupDownloadExporter(db.backupDao(), db.chapterDownloadingDao(), files, backupTestCbzReader(files), artifacts)
    val exporter = BackupExporter(mergeWriter, files, downloads, FixedBackupExportProvenance("1.0.0", "test"))
    return BackupRepositoryImpl(
        exporter, BackupImporter(db.backupDao(), mergeWriter, preflight, publisher), BackupTestDispatchers, operations,
    )
}

internal fun backupTestCbzReader(files: AppFileSystem): DefaultCbzReader =
    DefaultCbzReader(files, BackupTestDispatchers, DesktopPageMediaInspector())

/** Same JVM ZIP writer used by the existing bounded-reader fixtures, without replacing admission. */
internal fun deflatedBackupArchive(vararg entries: Pair<String, ByteArray>): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return buffer.toByteArray()
}

internal fun backupTestDatabase(): MangaDatabase = Room.inMemoryDatabaseBuilder<MangaDatabase>()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Unconfined)
    .build()

/** Real owner/progress writers over the caller's database; no URL-only progress substitute. */
internal class BackupTestProgressRuntime(db: MangaDatabase) {
    private val transactions = LibraryObservedTransactions(RoomMangaWriteTransaction(db))
    private val snapshots = LibraryControlledSnapshots(transactions, libraryPolicy())
    private val storage = ProgressStorage(
        db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao(),
    )
    private val owners = ProgressOwnerTransactions(transactions, snapshots, storage)
    private val settings = LegacyProgressSettings(
        ProgressTestSettings(), LegacyProgressSettingsGate(), ProgressTestWriterOwnership(),
    )
    val native = RoomScopedReadProgressRepository(owners)
    val mergeWriter = BackupMergeWriter(owners, settings)
}

internal class BackupTestFileSystem(label: String) : AppFileSystem {
    private val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "kira-backup-$label-${Random.nextLong().toString().trimStart('-')}"
    override val filesDir: Path = root / "files"
    override val cacheDir: Path = root / "cache"

    override fun fileSystem(): FileSystem = FileSystem.SYSTEM

    fun cleanUp() = fileSystem().deleteRecursively(root, mustExist = false)
}

internal data object BackupTestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
