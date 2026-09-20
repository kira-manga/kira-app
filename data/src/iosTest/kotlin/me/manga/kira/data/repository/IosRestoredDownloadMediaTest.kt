package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.cbz.IosCbzWriter
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import okio.FileMetadata
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room/file-backed absence tests. Reopen is not iPhone backup, WAL or OS-restore evidence. */
class IosRestoredDownloadMediaTest {
    @Test
    fun partialRestoredTreesClearOnlyDerivedMetadataAndKeepFilesBudgetsAndLedgerIdentity() = runTest {
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING, DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            for (retainedManifest in listOf(false, true)) {
                val fixture = IosCbzFinalizationFixture()
                val host = SupervisorJob(coroutineContext[Job])
                try {
                    val chapter = fixture.downloadNamed(fixture.seed())
                    fixture.prepareAttempt(chapter, state, failures = 2, pageCount = 2)
                    val record = assertNotNull(fixture.db.chapterArtifactDao().get(chapter.saved.id))
                    fixture.db.chapterArtifactDao().update(record.copy(
                        committedToken = "excluded-generation",
                        committedRelativePath = ChapterArtifactReference.restored("22222222-2222-4222-8222-222222222222"),
                    ))
                    val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                    val manifest = directory / "manifest.json"
                    val retained = fixture.manifest(chapter).let { current ->
                        current.copy(pages = current.pages.map { it.copy(policyRejected = it.index == 1) })
                    }
                    DownloadManifestStore(fixture.appFileSystem).write(retained)
                    val manifestBytes = fixture.system.read(manifest) { readByteArray() }
                    if (!retainedManifest) fixture.system.delete(manifest)
                    fixture.system.delete(directory / "image_1.png")
                    val stage = directory / ".manifest-abc.tmp"
                    fixture.system.write(stage) { writeUtf8("retained incomplete stage") }
                    val mirrors = fixture.offlineMirrors(chapter)
                    val foreignHistory = mirrors.second.copy(id = 0, api = "unrelated")
                    val foreignId = fixture.db.backupDao().insertHistoryRow(foreignHistory)
                    val parent = fixture.db.mangaDao().getMangaById(chapter.saved.mangaId)
                    val expected = fixture.download(chapter)
                    fixture.reopen()
                    val transport = ArtifactTestTransport(fixture.operations, ready = true)
                    val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport)
                    engine.reconcileInterruptedDownloads()
                    host.cancelAndJoin()

                    assertTrue(transport.enqueued.isEmpty(), "$state/$retainedManifest must require Retry")
                    assertTrue(transport.cancelled.isEmpty(), "Missing-file repair is not native-drain proof")
                    assertEquals(expected.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null),
                        fixture.download(chapter))
                    assertEquals(chapter.saved.copy(isDownloaded = false, localImagePaths = emptyList()), fixture.saved(chapter))
                    fixture.assertMirrorsCleared(mirrors)
                    assertEquals(foreignHistory.copy(id = foreignId), fixture.db.backupDao().getAllHistoryOnce().single { it.id == foreignId })
                    assertEquals(parent, fixture.db.mangaDao().getMangaById(chapter.saved.mangaId))
                    assertContentEquals(chapter.pages.values.first(), fixture.system.read(directory / "image_0.png") { readByteArray() })
                    assertEquals("retained incomplete stage", fixture.system.read(stage) { readUtf8() })
                    if (retainedManifest) {
                        assertContentEquals(manifestBytes, fixture.system.read(manifest) { readByteArray() })
                        assertEquals(retained, fixture.manifest(chapter))
                    } else assertFalse(fixture.system.exists(manifest))
                    assertNull(fixture.db.chapterArtifactDao().get(chapter.saved.id)?.token)
                    assertNull(fixture.db.chapterArtifactDao().get(chapter.saved.id)?.committedRelativePath)
                    assertNull(fixture.db.chapterArtifactDao().get(chapter.saved.id)?.committedToken)
                    fixture.reopen()
                    assertEquals(expected.id, fixture.download(chapter).id)
                    assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                    fixture.assertMirrorsCleared(mirrors)
                } finally {
                    host.cancelAndJoin()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun completeNamedRosterAndExistingRealArchivesAreNotMissingMedia() = runTest {
        for (kind in listOf("roster", "canonical", "committed")) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.downloadNamed(fixture.seed())
                val state = if (kind == "roster") DownloadingState.RUNNING else DownloadingState.COMPRESSING
                val claim = fixture.prepareAttempt(chapter, state, pageCount = 2)
                val retainedFiles = if (kind == "roster") chapter.pages else {
                    var archive = IosCbzWriter(fixture.appFileSystem).createCbz(
                        chapter.pages.keys.toList(), chapter.saved.mangaId, chapter.saved.id, quality = 75,
                    )
                    if (kind == "committed") {
                        val relative = ChapterArtifactReference.restored("11111111-1111-4111-8111-111111111111")
                        val target = ChapterArtifactReference.resolve(fixture.appFileSystem, ChapterArtifactOwner.of(chapter.saved), relative)
                        fixture.system.createDirectories(assertNotNull(target.parent))
                        fixture.system.atomicMove(archive, target)
                        archive = target
                        val record = assertNotNull(fixture.db.chapterArtifactDao().get(chapter.saved.id))
                        fixture.db.chapterArtifactDao().update(record.copy(committedToken = "prior", committedRelativePath = relative))
                    }
                    mapOf(archive to fixture.system.read(archive) { readByteArray() })
                }
                val saved = fixture.saved(chapter)
                val record = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                val manifest = fixture.manifest(chapter)
                fixture.reopen()
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport)
                engine.reconcileInterruptedDownloads() // No foreground/OS compression window is granted.
                host.cancelAndJoin()
                assertTrue(transport.enqueued.isEmpty())
                assertTrue(fixture.saved(chapter).isDownloaded)
                assertEquals(claim.token, fixture.db.chapterArtifactDao().get(chapter.saved.id)?.token)
                assertEquals(manifest, fixture.manifest(chapter))
                if (kind == "roster") assertEquals(DownloadingState.DOWNLOADED, fixture.download(chapter).state) else {
                    assertEquals(state, fixture.download(chapter).state)
                    assertEquals(saved, fixture.saved(chapter))
                    assertEquals(record, fixture.db.chapterArtifactDao().get(chapter.saved.id))
                }
                retainedFiles.forEach { (path, bytes) -> assertContentEquals(bytes, fixture.system.read(path) { readByteArray() }) }
            } finally {
                host.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun unknownStorageNativeInventoryForeignPathsAndSymlinksNeverBecomeAbsence() = runTest {
        for (uncertain in listOf("storage", "native", "foreign", "symlink", "duplicate")) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.downloadNamed(fixture.seed())
                fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2, pageCount = 2)
                val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                fixture.system.delete(directory / "image_1.png")
                when (uncertain) {
                    "foreign" -> {
                        fixture.system.delete(directory / "image_0.png")
                        fixture.db.backupDao().updateChapterRow(chapter.saved.copy(
                            localImagePaths = listOf((fixture.appFileSystem.filesDir / "other/missing.png").toString()),
                        ))
                    }
                    "symlink" -> fixture.system.createSymlink(directory / "image_1.png", directory / "absent-target.png")
                    "duplicate" -> fixture.system.write(directory / "image_0.jpg") { write(chapter.pages.values.first()) }
                }
                val guarded = object : ForwardingFileSystem(fixture.system) {
                    override fun metadataOrNull(path: Path): FileMetadata? {
                        if (uncertain == "storage" && path == directory / "image_0.png") throw IOException("fixture metadata unavailable")
                        return super.metadataOrNull(path)
                    }
                }
                val files = object : AppFileSystem by fixture.appFileSystem { override fun fileSystem() = guarded }
                val expected = fixture.download(chapter)
                val saved = fixture.saved(chapter)
                val record = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                val manifest = fixture.manifest(chapter)
                fixture.reopen()
                val runtime = ArtifactTestRuntime(fixture.db, files, IosPageMediaInspector(system = fixture.system))
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                val native = object : BackgroundTransport by transport {
                    override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> {
                        if (uncertain == "native") throw IOException("fixture inventory unavailable")
                        return transport.inFlightPages(chapterId, attemptToken)
                    }
                }
                val engine = fixture.engine(CoroutineScope(coroutineContext + host), native, files = files, downloadArtifacts = runtime.downloads)
                engine.reconcileInterruptedDownloads()
                host.cancelAndJoin()
                assertEquals(expected, fixture.download(chapter), uncertain)
                assertEquals(saved, fixture.saved(chapter), uncertain)
                assertEquals(record, fixture.db.chapterArtifactDao().get(chapter.saved.id), uncertain)
                assertEquals(manifest, fixture.manifest(chapter), uncertain)
                assertTrue(transport.enqueued.isEmpty())
                if (uncertain == "symlink") assertNotNull(fixture.system.metadata(directory / "image_1.png").symlinkTarget)
            } finally {
                host.cancelAndJoin()
                fixture.close()
            }
        }
    }
}
