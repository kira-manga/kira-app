package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Existing real iOS repository/Room fixture. No modeled state machine or OS-termination claim. */
class IosManifestRecoverySafetyTest {
    @Test
    fun startupAttachesBeforeRecoveryButMutationStillWaitsForAdmission() = runTest {
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING)) {
            for (retainedToken in listOf(true, false)) {
                val fixture = IosCbzFinalizationFixture()
                val host = SupervisorJob(coroutineContext[Job])
                var held: HeldArtifactRecovery? = null
                try {
                    val chapter = fixture.seed()
                    val token = if (retainedToken) fixture.prepareAttempt(chapter, state).token else {
                        fixture.dao.updateStateChId(chapter.saved.id, state)
                        null
                    }
                    val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                    chapter.pages.keys.forEach { fixture.system.delete(it) }
                    fixture.system.delete(directory / "manifest.json", mustExist = false)
                    val expected = fixture.download(chapter)
                    fixture.reopen()
                    val recovery = HeldArtifactRecovery(fixture.db.chapterArtifactDao()).also { held = it }
                    val runtime = ArtifactTestRuntime(
                        recovery, fixture.db.chapterArtifactCommitDao(), fixture.appFileSystem,
                        IosPageMediaInspector(system = fixture.system),
                    )
                    var resolutions = 0
                    val provider = object : ChapterPageProvider {
                        override suspend fun pagesOrNull(
                            api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String,
                        ): List<DownloadPage> {
                            resolutions++
                            return List(2) { DownloadPage("https://example.test/page-$it.png", emptyMap()) }
                        }
                    }
                    val transport = ArtifactTestTransport(fixture.operations, ready = true)
                    var transportStarts = 0
                    val observedTransport = object : BackgroundTransport by transport {
                        override suspend fun ensureReady() {
                            transportStarts++
                            transport.ensureReady()
                        }
                    }
                    val scope = CoroutineScope(coroutineContext + host)
                    val engine = fixture.engine(
                        scope, observedTransport, downloadArtifacts = runtime.downloads, pageProvider = provider,
                    )
                    recovery.entered.await()
                    val reconcile = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.reconcileInterruptedDownloads()
                    }
                    assertEquals(2, transportStarts, "Startup and explicit reconcile attach independently of artifact recovery")
                    assertEquals(0, resolutions)
                    assertFalse(reconcile.isCompleted)
                    assertTrue(transport.enqueued.isEmpty())
                    assertEquals(expected, fixture.download(chapter))
                    assertFalse(fixture.system.exists(directory / "manifest.json"))

                    recovery.release.complete(Unit)
                    reconcile.join()
                    val request = transport.requests.receive()
                    host.cancelAndJoin()
                    assertEquals(1, resolutions, "Readiness must not replace the existing automatic restart policy")
                    assertEquals(expected.id, fixture.download(chapter).id)
                    assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
                    assertTrue(request.attemptToken.isNotBlank())
                    if (token != null) assertEquals(token, request.attemptToken)
                    assertEquals(request.attemptToken, fixture.manifest(chapter).attemptToken)
                    assertEquals(listOf(0, 0), fixture.manifest(chapter).pages.map { it.attempts })
                } finally {
                    held?.release?.complete(Unit)
                    host.cancelAndJoin()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun completionAndFailureCallbacksWaitForRecoveryBeforeUsingTheRetainedAttempt() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        var held: HeldArtifactRecovery? = null
        var recovering: Job? = null
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 1, pageCount = 2)
            chapter.pages.keys.forEach { fixture.system.delete(it) }
            val expected = fixture.download(chapter)
            val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
            val before = fixture.system.read(directory / "manifest.json") { readByteArray() }
            fixture.reopen()
            val recovery = HeldArtifactRecovery(fixture.db.chapterArtifactDao()).also { held = it }
            val runtime = ArtifactTestRuntime(
                recovery, fixture.db.chapterArtifactCommitDao(), fixture.appFileSystem,
                IosPageMediaInspector(system = fixture.system),
            )
            // Hold the real coordinator independently of startup, including on the old callback path.
            recovering = launch(start = CoroutineStart.UNDISPATCHED) { runtime.ownership.read(chapter.saved.id) {} }
            recovery.entered.await()
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport, downloadArtifacts = runtime.downloads)
            val page = ReceiverPage(fixture, chapter, "readiness-complete", failPublication = false)
            val completed = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            var completionReceipts = 0
            var failureReceipts = 0
            fixture.operations.withOperation { operation ->
                engine.onPageComplete(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, page.page, operation) {
                    completionReceipts++
                    completed.complete(Unit)
                }
            }
            fixture.operations.withOperation { operation ->
                engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 1, claim.token, "transfer failed", operation) {
                    failureReceipts++
                    failed.complete(Unit)
                }
            }
            // Both callbacks launch UNDISPATCHED: a bypass increments get() before Room can suspend.
            assertEquals(0, recovery.claimReads)
            assertEquals(0, page.publications)
            assertFalse(completed.isCompleted)
            assertFalse(failed.isCompleted)
            assertFalse(page.discarded.isCompleted)
            assertTrue(fixture.system.exists(page.path))
            assertEquals(expected, fixture.download(chapter))
            assertContentEquals(before, fixture.system.read(directory / "manifest.json") { readByteArray() })

            recovery.release.complete(Unit)
            completed.await()
            failed.await()
            recovering.join()
            host.cancelAndJoin()
            assertEquals(1, completionReceipts)
            assertEquals(1, failureReceipts)
            assertEquals(1, page.publications)
            assertFalse(fixture.system.exists(page.path))
            assertContentEquals(chapter.pages.values.last(), fixture.system.read(directory / "image_0.png") { readByteArray() })
            assertFalse(fixture.system.exists(directory / "image_1.png"))
            assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
            assertEquals(50, fixture.download(chapter).progress)
            assertEquals(claim.token, fixture.manifest(chapter).attemptToken)
            assertEquals(listOf(1, 2), fixture.manifest(chapter).pages.map { it.attempts })
            assertTrue(fixture.manifest(chapter).pages.none { it.policyRejected })
        } finally {
            host.cancelAndJoin()
            recovering?.cancelAndJoin()
            held?.release?.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun failedOrCancelledRecoveryDisposesAndAcknowledgesWithoutCallbackFallbackMutation() = runTest {
        for (cancel in listOf(false, true)) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            var held: HeldArtifactRecovery? = null
            var recovering: Job? = null
            try {
                val chapter = fixture.seed()
                val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 1, pageCount = 2)
                chapter.pages.keys.forEach { fixture.system.delete(it) }
                val expected = fixture.download(chapter)
                val record = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
                val before = fixture.system.read(path) { readByteArray() }
                fixture.reopen()
                val recovery = HeldArtifactRecovery(fixture.db.chapterArtifactDao(), failAfterRelease = !cancel).also { held = it }
                val runtime = ArtifactTestRuntime(
                    recovery, fixture.db.chapterArtifactCommitDao(), fixture.appFileSystem,
                    IosPageMediaInspector(system = fixture.system),
                )
                val recoveryFailed = CompletableDeferred<IOException>()
                recovering = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        runtime.ownership.read(chapter.saved.id) {}
                    } catch (failure: IOException) {
                        recoveryFailed.complete(failure)
                    }
                }
                recovery.entered.await()
                val transport = ArtifactTestTransport(fixture.operations)
                val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport, downloadArtifacts = runtime.downloads)
                var discards = 0
                val page = ReceiverPage(fixture, chapter, "readiness-aborted", failPublication = false, beforeDiscard = { discards++ })
                val completed = CompletableDeferred<Unit>()
                val failed = CompletableDeferred<Unit>()
                var completionReceipts = 0
                var failureReceipts = 0
                fixture.operations.withOperation { operation ->
                    engine.onPageComplete(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, page.page, operation) {
                        completionReceipts++
                        completed.complete(Unit)
                    }
                }
                fixture.operations.withOperation { operation ->
                    engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 1, claim.token, "transfer failed", operation) {
                        failureReceipts++
                        failed.complete(Unit)
                    }
                }
                assertEquals(0, recovery.claimReads)
                assertFalse(completed.isCompleted)
                assertFalse(failed.isCompleted)
                if (cancel) {
                    host.cancelAndJoin()
                    assertFalse(recovery.release.isCompleted, "Callback cancellation must not wait for recovery")
                    assertFalse(recovering.isCompleted)
                } else {
                    recovery.release.complete(Unit)
                    assertEquals("startup artifact recovery unavailable", recoveryFailed.await().message)
                }
                completed.await()
                failed.await()
                host.cancelAndJoin()
                assertEquals(1, completionReceipts)
                assertEquals(1, failureReceipts)
                assertEquals(1, discards)
                assertEquals(0, page.publications)
                assertTrue(page.discarded.isCompleted)
                assertFalse(fixture.system.exists(page.path))
                assertEquals(0, recovery.claimReads, "Readiness refusal must not enter callback failure bookkeeping")
                assertEquals(expected, fixture.download(chapter))
                assertEquals(record, fixture.db.chapterArtifactDao().get(chapter.saved.id))
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
                assertTrue(transport.enqueued.isEmpty())
            } finally {
                host.cancelAndJoin()
                recovering?.cancelAndJoin()
                held?.release?.complete(Unit)
                fixture.close()
            }
        }
    }

    @Test
    fun secondaryCallbackPersistenceFailureStillAcknowledgesWithoutEscaping() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
            val expected = fixture.download(chapter)
            val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
            val before = fixture.system.read(path) { readByteArray() }
            var failedReads = 0
            val downloads = object : ChapterDownloadDao by fixture.dao {
                override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                    failedReads++
                    throw IOException("callback ledger read unavailable")
                }
            }
            // The existing fixture keeps startup blocked, isolating the actual delegate callback.
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport, downloads = downloads)
            val acknowledged = CompletableDeferred<Unit>()
            var receipts = 0
            fixture.operations.withOperation { operation ->
                engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, "transfer failed", operation) {
                    receipts++
                    acknowledged.complete(Unit)
                }
            }
            acknowledged.await()
            host.cancelAndJoin() // runTest also rejects any unhandled exception from the callback job.
            assertEquals(2, failedReads, "One attempt and one bounded recovery; no recursive retry")
            assertEquals(1, receipts)
            assertEquals(expected, fixture.download(chapter))
            assertContentEquals(before, fixture.system.read(path) { readByteArray() })
            assertTrue(transport.enqueued.isEmpty())
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun unreadableOrForeignManifestFailsClosedWithoutResolveAndReadableExplicitRetryStillWorks() = runTest {
        for (failure in listOf("corrupt", "read", "api", "token")) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            val retryHost = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.seed()
                val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
                chapter.pages.keys.forEach { fixture.system.delete(it) }
                val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
                when (failure) {
                    "corrupt" -> fixture.system.write(path) { writeUtf8("{torn manifest") }
                    "api" -> DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(chapter).copy(api = "other"))
                    "token" -> DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(chapter).copy(attemptToken = "foreign"))
                }
                val before = fixture.system.read(path) { readByteArray() }
                val guarded = object : ForwardingFileSystem(fixture.system) {
                    override fun source(file: Path): Source {
                        if (failure == "read" && file == path) throw IOException("manifest read unavailable")
                        return super.source(file)
                    }
                }
                val files = object : AppFileSystem by fixture.appFileSystem {
                    override fun fileSystem() = guarded
                }
                var resolutions = 0
                val provider = object : ChapterPageProvider {
                    override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
                        resolutions++
                        error("Retained bytes cannot authorize a new scrape")
                    }
                }
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                fixture.engine(CoroutineScope(coroutineContext + host), transport, files = files, pageProvider = provider)
                fixture.dao.observeAllDownloads().first { rows ->
                    rows.any { it.chapterId == chapter.saved.id && it.state == DownloadingState.FAILED }
                }
                host.cancelAndJoin()
                assertEquals(0, resolutions)
                assertTrue(transport.enqueued.isEmpty())
                assertEquals("Download manifest could not be read", fixture.download(chapter).errorMsg)
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
                fixture.reopen()
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })

                if (failure == "read") {
                    // Storage is healthy now. Only explicit user Retry may grant a fresh ordinary budget.
                    assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
                    val retryTransport = ArtifactTestTransport(fixture.operations, ready = true)
                    val engine = fixture.engine(CoroutineScope(coroutineContext + retryHost), retryTransport, pageProvider = provider)
                    assertTrue(engine.retryChapterDownload(fixture.download(chapter)))
                    val request = retryTransport.requests.receive()
                    assertNotEquals(claim.token, request.attemptToken)
                    assertEquals(0, fixture.manifest(chapter).pages.single().attempts)
                    assertEquals(0, resolutions)
                }
            } finally {
                host.cancelAndJoin()
                retryHost.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun startupReclaimsOnlyOwnedManifestStagesAndResumesTheUnchangedDurableBudget() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
            chapter.pages.keys.forEach { fixture.system.delete(it) }
            val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
            val manifest = directory / "manifest.json"
            val before = fixture.system.read(manifest) { readByteArray() }
            val orphan = directory / ".manifest-123abc.tmp"
            val unrelated = directory / ".manifest-nothex.tmp"
            val stageDirectory = directory / ".manifest-f.tmp"
            val otherChapter = directory.parent!! / "chapter_9999" / ".manifest-abc.tmp"
            fixture.system.createDirectories(stageDirectory)
            fixture.system.createDirectories(otherChapter.parent!!)
            listOf(orphan, unrelated, stageDirectory / "keep", otherChapter).forEach { file ->
                fixture.system.write(file) { writeUtf8("retained fixture bytes") }
            }
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            fixture.engine(CoroutineScope(coroutineContext + host), transport)
            val request = transport.requests.receive()
            assertEquals(claim.token, request.attemptToken)
            assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
            assertContentEquals(before, fixture.system.read(manifest) { readByteArray() })
            assertFalse(fixture.system.exists(orphan))
            listOf(unrelated, stageDirectory / "keep", otherChapter).forEach { assertTrue(fixture.system.exists(it)) }
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }
}

/** Gate only the real startup query; every unmodified DAO operation still goes to file-backed Room. */
private class HeldArtifactRecovery(
    private val delegate: ChapterArtifactDao,
    private val failAfterRelease: Boolean = false,
) : ChapterArtifactDao by delegate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var claimReads = 0
        private set

    override suspend fun getUnsettled(): List<ChapterArtifactEntity> {
        entered.complete(Unit)
        release.await()
        if (failAfterRelease) throw IOException("startup artifact recovery unavailable")
        return delegate.getUnsettled()
    }

    override suspend fun get(chapterId: Long): ChapterArtifactEntity? {
        claimReads++
        return delegate.get(chapterId)
    }
}
