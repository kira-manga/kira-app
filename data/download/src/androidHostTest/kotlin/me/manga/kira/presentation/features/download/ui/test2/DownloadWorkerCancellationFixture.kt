package me.manga.kira.presentation.features.download.ui.test2

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val GATE_TIMEOUT_SECONDS = 15L
internal const val GATE_TIMEOUT_MILLIS = GATE_TIMEOUT_SECONDS * 1_000L
private const val CASE_TIMEOUT_MILLIS = 60_000L
internal const val FULL_BUFFER_PAGES = 65
internal const val PARTIAL_PAGES = 2
internal const val DOWNLOAD_COMPLETION_PROGRESS = 100
private const val PARTIAL_PROGRESS = 50
internal const val FIXTURE_API = "app75-host-fixture"
internal const val USER_CANCELLED = "__cancelled_by_user__"

internal enum class CancellationSeam {
    DELIVERED_SEND,
    COMMITTED_RETURN,
    PARTIAL_SYSTEM,
    PARTIAL_FAILED,
    ;

    val partial: Boolean get() = this == PARTIAL_SYSTEM || this == PARTIAL_FAILED
    val pageCount: Int get() = if (this == DELIVERED_SEND) FULL_BUFFER_PAGES else PARTIAL_PAGES
}

internal fun cancellationFixture(
    seam: CancellationSeam,
    block: suspend DownloadWorkerCancellationFixture.() -> Unit,
) = runBlocking {
    check(GlobalContext.getOrNull() == null) { "Refusing to replace another test's global Koin" }
    val fixture = DownloadWorkerCancellationFixture(seam)
    var primaryFailure: Throwable? = null
    try {
        runCatching {
            withTimeout(CASE_TIMEOUT_MILLIS) {
                fixture.prepare()
                fixture.block()
            }
        }.onFailure { primaryFailure = it }.getOrThrow()
    } finally {
        withContext(NonCancellable) { closeWithPrimaryFailure(fixture, primaryFailure) }
    }
}

private suspend fun closeWithPrimaryFailure(
    fixture: DownloadWorkerCancellationFixture,
    primaryFailure: Throwable?,
) {
    val cleanupFailure = runCatching { fixture.close() }.exceptionOrNull() ?: return
    if (primaryFailure == null) throw cleanupFailure
    primaryFailure.addSuppressed(cleanupFailure)
}

/** Composition only; all tested lifecycle decisions remain in the real worker/service/Room code. */
internal class DownloadWorkerCancellationFixture(
    private val seam: CancellationSeam,
) {
    val storage = CancellationFixtureStorage(RuntimeEnvironment.getApplication())
    val commit = NativeCommitGate()
    val worker = DownloadJobWitness("worker")
    val producer = DownloadJobWitness("producer")
    val sender = CompleteSendDispatcher(producer)
    val rows = DownloadWorkerCancellationRows(storage, commit)
    private val daoHolder = lazy { DownloadWorkerCancellationDao(rows, seam, worker, sender, commit) }
    val dao: DownloadWorkerCancellationDao get() = daoHolder.value
    private val transportHolder = lazy { CancellationPageTransport(seam, rows) }
    val transport: CancellationPageTransport get() = transportHolder.value
    val paths: List<String> get() = storage.imagePaths(rows.original.saved.id, seam.pageCount)
    private var koin: KoinApplication? = null
    private var instance: DownloadWorkerV2? = null
    private var future: ListenableFuture<ListenableWorker.Result>? = null
    private val foregroundCalls = AtomicInteger()

    suspend fun prepare() {
        rows.seed()
        storage.settings.setUseCbzFormat(false)
        val service = fixtureDownloadService(storage, rows, dao, transport, sender)
        koin =
            startKoin {
                modules(
                    module {
                        single<ChapterDownloadDao> { dao }
                        single<MangaDao> { rows.db.mangaDao() }
                        single { service }
                        single { rows.artifacts }
                        single<ChapterPageProvider> { transport.provider }
                        single<AppFileSystem> { storage.fileSystem }
                    },
                )
            }
    }

    suspend fun start() {
        check(instance == null)
        val actual =
            TestListenableWorkerBuilder<DownloadWorkerV2>(storage.context)
                .setForegroundUpdater(ImmediateFixtureForegroundUpdater(foregroundCalls))
                .build()
        instance = actual
        // Test runner's main looper is PAUSED; the bounded immediate test updater needs no pumping.
        future = actual.startWork()
        withTimeout(GATE_TIMEOUT_MILLIS) { worker.entered.await() }
        assertTrue(foregroundCalls.get() > 0)
    }

    suspend fun stopAndObserveCancellation() {
        val actual = checkNotNull(instance)
        // Exact Work2.11.2 awaitWithin cancellation order, pinned to installed bytecode in author packet.
        actual.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        assertTrue(checkNotNull(future).cancel(false))
        assertTrue(actual.isStopped)
        assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, actual.stopReason)
        worker.awaitCancellation()
        receipt("stop-reason=${actual.stopReason}; startWork-future-cancelled")
    }

    suspend fun joinJobs() {
        worker.join()
        producer.join()
        assertTrue(checkNotNull(future).isCancelled)
    }

    suspend fun partialCheckpoint() {
        withTimeout(GATE_TIMEOUT_MILLIS) {
            transport.secondResponseEntered.await()
            dao.lastProgressReturned.await()
        }
        assertEquals(PARTIAL_PAGES, transport.requests.get())
        assertEquals(PARTIAL_PAGES, dao.progressCalls.get())
        storage.assertImages(paths.take(1))
        assertFalse(File(paths.last()).exists())
        assertEquals(
            rows.original.download.copy(state = DownloadingState.RUNNING, progress = PARTIAL_PROGRESS),
            rows.download(),
        )
        assertEquals(rows.original.saved, rows.saved())
        receipt("first-real-file-read; second-response-suspended; real-ledger-RUNNING")
    }

    suspend fun assertCommitted() {
        assertEquals(1, dao.completionCalls.get())
        rows.assertCompleted(paths)
        storage.assertImages(paths)
        val actualFiles = checkNotNull(File(paths.first()).parentFile.listFiles()).map { it.path }
        assertEquals(paths.sorted(), actualFiles.sorted())
    }

    suspend fun assertPartialStopped(
        state: DownloadingState,
        error: String?,
    ) {
        val progress = if (state == DownloadingState.QUEUED) 0 else PARTIAL_PROGRESS
        assertEquals(
            rows.original.download.copy(state = state, progress = progress, errorMsg = error),
            rows.download(),
        )
        assertEquals(rows.original.saved, rows.saved())
        assertFalse(checkNotNull(File(paths.first()).parentFile).exists())
    }

    fun receipt(event: String) {
        println("APP75 seam=$seam chapter=${rows.original.saved.id} gate=$event")
    }

    suspend fun close() {
        if (daoHolder.isInitialized()) dao.releaseProgress.complete(Unit)
        if (transportHolder.isInitialized()) transport.releaseSecondResponse.complete(Unit)
        commit.release()
        sender.release()
        instance?.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        future?.cancel(false)
        worker.job?.cancel()
        producer.job?.cancel()
        val failures = mutableListOf<Throwable>()
        runCatching {
            withTimeout(GATE_TIMEOUT_MILLIS) {
                worker.job?.join()
                producer.job?.join()
            }
        }.exceptionOrNull()?.let(failures::add)
        closeFixtureResources(this, failures) {
            check(worker.job?.isCompleted != false && producer.job?.isCompleted != false) {
                "Retaining owned files: actual download Jobs did not terminate"
            }
            check(future == null || worker.job != null) { "Worker never reached its Job capture seam" }
            storage.removeOwnedFiles()
        }
    }

    internal fun resourceClosers(): List<() -> Unit> =
        listOf(
            { if (transportHolder.isInitialized()) transport.close() },
            { rows.close() },
            { sender.close() },
            {
                koin?.let { owned ->
                    check(GlobalContext.getOrNull() === owned.koin) { "Global Koin ownership changed" }
                    stopKoin()
                }
            },
            { storage.restoreProperties() },
        )
}

private fun closeFixtureResources(
    fixture: DownloadWorkerCancellationFixture,
    failures: MutableList<Throwable>,
    removeOwnedFiles: () -> Unit,
) {
    fixture.resourceClosers().forEach { close ->
        runCatching(close).exceptionOrNull()?.let(failures::add)
    }
    // Retain the owned native/database/files if any join or resource close failed.
    if (failures.isEmpty()) {
        runCatching(removeOwnedFiles).exceptionOrNull()?.let(failures::add)
    }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}

private class ImmediateFixtureForegroundUpdater(
    private val calls: AtomicInteger,
) : ForegroundUpdater {
    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: ForegroundInfo,
    ): ListenableFuture<Void> =
        CallbackToFutureAdapter.getFuture { completer ->
            calls.incrementAndGet()
            completer.set(null)
            "App75 test foreground updater (not an Android foreground service)"
        }
}
