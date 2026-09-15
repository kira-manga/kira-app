package me.manga.kira.data.backup

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.Buffer
import okio.ByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink

internal fun restoredDownloadTest(block: suspend RestoredDownloadFixture.() -> Unit) = runTest {
    val fixture = RestoredDownloadFixture()
    try {
        fixture.seed()
        fixture.block()
    } finally {
        fixture.close()
    }
}

/** Real Room and filesystem; fault hooks affect only the one privately generated archive. */
internal class RestoredDownloadFixture {
    private val root = Files.createTempDirectory("kira-restore-owner-").toString().toPath()
    private val databasePath = root / "test.db"
    val fs = RestoreFaultFileSystem()
    val appFs = object : AppFileSystem {
        override val filesDir = root / "files"
        override val cacheDir = root / "cache"
        override fun fileSystem(): FileSystem = fs
    }
    var db: MangaDatabase = openDatabase()
        private set
    lateinit var saved: SavedChapterEntity
        private set
    lateinit var row: ChapterDownloadEntity
        private set
    val source = RestoredChapterArchive(root / "validated.cbz", CONTENT.size.toLong())
    val canonical: Path get() = appFs.filesDir / "manga/${saved.mangaId}/chapter_${saved.id}/chapter_${saved.id}.cbz"
    private var commits: ChapterArtifactCommitDao = db.chapterArtifactCommitDao()
    private var recovery = ChapterArtifactRecovery(db.chapterArtifactDao(), commits, appFs)
    var artifacts = ChapterArtifacts(db.chapterArtifactDao(), recovery)
        private set

    suspend fun seed() {
        val mangaId = db.backupDao().insertMangaRow(
            SavedMangaEntity(
                api = "test", language = "en", url = "https://example.test/manga/1", imageUrl = "",
                title = "Test", description = "", status = "ongoing", rating = null, genres = emptyList(),
                savedTimestamp = 1, lastOpenTimestamp = 1,
            ),
        )
        val chapter = SavedChapterEntity(mangaId = mangaId, name = "Chapter 1", number = "1", url = "https://example.test/chapter/1")
        saved = chapter.copy(id = db.backupDao().insertChapterRow(chapter))
        row = ChapterDownloadEntity(
            number = "1", chapterId = saved.id, mangaId = mangaId, api = "test", url = saved.url,
            state = DownloadingState.QUEUED, progress = 0,
        )
        fs.write(source.path) { write(CONTENT) }
        fs.createDirectories(checkNotNull(canonical.parent))
        fs.write(canonical) { writeUtf8("incumbent-canonical") }
    }

    suspend fun publish(expected: SavedChapterEntity = saved) =
        RestoredDownloadPublisher(artifacts, db.chapterArtifactDao(), commits, appFs, recovery)
            .publish(source, expected, "test", "Test")

    /** Reconstruct a persisted copy cut with real admission and exclusive-path custody, not a crash hook. */
    suspend fun stageRestorePart(content: ByteArray): Pair<ChapterArtifactClaim, Path> {
        val claim = checkNotNull(artifacts.beginRestore(saved, source.sizeBytes))
        val target = ChapterArtifactReference.resolve(appFs, claim.owner, checkNotNull(claim.relativePath))
        val directory = checkNotNull(target.parent)
        fs.createDirectories(checkNotNull(directory.parent))
        fs.createDirectory(directory, mustCreate = true)
        check(db.chapterArtifactDao().confirmPendingPathOwnership(saved.id, claim.token) == 1)
        fs.write(partPath(target), mustCreate = true) { write(content) }
        return claim to target
    }

    fun partPath(target: Path): Path = checkNotNull(target.parent) / "${target.name}.part"

    /** Same-manga controls catch overbroad cleanup without bypassing restore's unreadable-only admission. */
    suspend fun seedRecoveryControls(): List<RestoreChapterSnapshot> {
        val readable = seedSibling("2")
        check(publish(readable) == ChapterRestoreOutcome.COMMITTED)
        val restored = checkNotNull(db.chapterDao().getChapterByIdSuspend(readable.id))
        check(restored.isDownloaded)
        val queued = seedSibling("3")
        val claim = checkNotNull(artifacts.enqueue(
            queued, row.copy(number = queued.number, chapterId = queued.id, url = queued.url),
        ))
        val page = appFs.chapterDir(queued.mangaId, queued.id) / "image_0.png"
        check(artifacts.files(claim) {
            fs.createDirectories(checkNotNull(page.parent))
            fs.write(page, mustCreate = true) { writeUtf8("unrelated-queued-page") }
            true
        } == true)
        return listOf(
            snapshot(restored.id, listOf(restored.localImagePaths.single().toPath())),
            snapshot(queued.id, listOf(page)),
        )
    }

    private suspend fun seedSibling(number: String): SavedChapterEntity {
        val chapter = saved.copy(
            id = 0, name = "Chapter $number", number = number, url = "https://example.test/chapter/$number",
        )
        return chapter.copy(id = db.backupDao().insertChapterRow(chapter))
    }

    suspend fun snapshot(chapterId: Long, paths: Collection<Path> = emptyList()): RestoreChapterSnapshot =
        RestoreChapterSnapshot(
            checkNotNull(db.chapterDao().getChapterByIdSuspend(chapterId)),
            db.chapterArtifactDao().download(chapterId), db.chapterArtifactDao().get(chapterId),
            paths.associateWith { path -> fs.read(path) { readByteString() } },
        )

    fun decorateCommits(decorate: (ChapterArtifactCommitDao) -> ChapterArtifactCommitDao) {
        commits = decorate(db.chapterArtifactCommitDao())
        recovery = ChapterArtifactRecovery(db.chapterArtifactDao(), commits, appFs)
        artifacts = ChapterArtifacts(db.chapterArtifactDao(), recovery)
    }

    /** Graceful database close/reopen plus new ownership gates; not physical process termination. */
    fun reopen() {
        db.close()
        db = openDatabase()
        decorateCommits { it }
    }

    private fun openDatabase(): MangaDatabase = Room.databaseBuilder<MangaDatabase>(name = databasePath.toString())
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()

    fun close() {
        db.close()
        FileSystem.SYSTEM.deleteRecursively(root)
    }

    companion object {
        // This suite tests already-validated byte publication, not App9's ZIP/media admission.
        val CONTENT = "privately-validated-archive-content".encodeToByteArray()
    }
}

internal data class RestoreChapterSnapshot(
    val saved: SavedChapterEntity,
    val download: ChapterDownloadEntity?,
    val artifact: ChapterArtifactEntity?,
    val files: Map<Path, ByteString>,
)

internal class RestoreFaultFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
    var duringCopy: (() -> Unit)? = null
    var afterPromotion: (() -> Unit)? = null

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val delegate = super.sink(file, mustCreate)
        return object : ForwardingSink(delegate) {
            override fun write(source: Buffer, byteCount: Long) {
                if (file.name.endsWith(".part")) {
                    duringCopy?.also { duringCopy = null }?.invoke()
                }
                super.write(source, byteCount)
            }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        super.atomicMove(source, target)
        afterPromotion?.also { afterPromotion = null }?.invoke()
    }
}

/** Fault after/before the actual Room transaction, not a fake commit result treated as truth. */
internal class RestoreCommitFault(
    private val delegate: ChapterArtifactCommitDao,
    private val before: (() -> Unit)? = null,
    private val after: (() -> Unit)? = null,
    private val unreadableOutcome: Boolean = false,
) : ChapterArtifactCommitDao by delegate {
    override suspend fun commitRestore(
        claim: ChapterArtifactClaim,
        expected: SavedChapterEntity,
        absolutePath: String,
        requested: ChapterDownloadEntity,
    ): Boolean {
        before?.invoke()
        return delegate.commitRestore(claim, expected, absolutePath, requested).also { after?.invoke() }
    }

    override suspend fun readRestoreOutcome(claim: ChapterArtifactClaim, absolutePath: String, sizeBytes: Long) =
        if (unreadableOutcome) throw okio.IOException("readback unavailable")
        else delegate.readRestoreOutcome(claim, absolutePath, sizeBytes)
}
