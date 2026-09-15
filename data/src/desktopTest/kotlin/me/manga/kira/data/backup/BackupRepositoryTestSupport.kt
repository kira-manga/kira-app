package me.manga.kira.data.backup

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageMediaInspector
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/** Existing end-to-end Room/filesystem fixture, shared with production admission assertions. */
internal fun backupTestRepository(
    db: MangaDatabase,
    files: AppFileSystem,
    progress: ReadProgressRepository,
    inspector: PageMediaInspector = DesktopPageMediaInspector(),
    policy: BackupImportPolicy = BackupImportPolicy(),
): BackupRepositoryImpl {
    val recovery = ChapterArtifactRecovery(db.chapterArtifactDao(), db.chapterArtifactCommitDao(), files)
    val artifacts = ChapterArtifacts(db.chapterArtifactDao(), recovery)
    val publisher = RestoredDownloadPublisher(artifacts, db.chapterArtifactDao(), db.chapterArtifactCommitDao(), files, recovery)
    val preflight = BackupArchivePreflight(files, BackupImportStaging(files, policy), inspector, policy)
    val downloads = BackupDownloadExporter(db.backupDao(), db.chapterDownloadingDao(), files, backupTestCbzReader(files), artifacts)
    val exporter = BackupExporter(db.backupDao(), progress, files, downloads, FixedBackupExportProvenance("1.0.0", "test"))
    return BackupRepositoryImpl(exporter, BackupImporter(db.backupDao(), progress, preflight, publisher), BackupTestDispatchers)
}

internal fun backupTestCbzReader(files: AppFileSystem): DefaultCbzReader =
    DefaultCbzReader(files, BackupTestDispatchers, DesktopPageMediaInspector())

internal fun backupTestDatabase(): MangaDatabase = Room.inMemoryDatabaseBuilder<MangaDatabase>()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.Unconfined)
    .build()

internal class BackupMemoryReadProgress : ReadProgressRepository {
    private val values = mutableMapOf<String, Int>()

    override suspend fun save(chapterUrl: String, pageIndex: Int) {
        values[chapterUrl] = pageIndex
    }

    override suspend fun load(chapterUrl: String): Int? = values[chapterUrl]

    override suspend fun clear(chapterUrl: String) {
        values.remove(chapterUrl)
    }
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
