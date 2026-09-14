package me.manga.kira.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.util.notification.ChapterNotificationHelper
import me.manga.kira.core.util.notification.NotificationCoverDecoder
import me.manga.kira.core.util.notification.NotificationCoverLimits
import me.manga.kira.core.util.notification.NotificationCoverLoader
import me.manga.kira.core.util.notification.NotificationCovers
import me.manga.kira.core.util.notification.NotificationRoomFixture
import me.manga.kira.core.util.notification.notificationCoverCalls
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val NOTIFICATION_WAIT_MILLIS = 5_000L
private const val DECODE_RELEASE_SECONDS = 10L

/** Composition only: the real worker, repository, helper and Room still make all decisions. */
internal fun notificationRefreshWorker(
    context: Context,
    repository: LibraryRepository,
    helper: ChapterNotificationHelper,
    chapters: List<SavedChapterEntity>,
    beforeDetails: suspend () -> Unit = {},
): LibraryRefreshWorker =
    TestListenableWorkerBuilder<LibraryRefreshWorker>(context)
        .setWorkerFactory(
            refreshWorkerFactory(repository, helper, fixtureRegistry(RefreshSource(chapters, beforeDetails))),
        )
        .setForegroundUpdater(ImmediateRefreshForeground)
        .build()

private fun refreshWorkerFactory(
    repository: LibraryRepository,
    helper: ChapterNotificationHelper,
    registry: SourceRegistry,
): WorkerFactory =
    object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == LibraryRefreshWorker::class.java.name) {
                LibraryRefreshWorker(
                    appContext,
                    workerParameters,
                    repository,
                    SharedPrefsHelper(MapSettings()),
                    helper,
                    registry,
                )
            } else {
                null
            }
    }

private class RefreshSource(
    private val chapters: List<SavedChapterEntity>,
    private val beforeDetails: suspend () -> Unit,
) : MangaSourceClient {
    override val api = "notification-fixture"

    override suspend fun home(page: Int): Nothing = error("Unexpected home request")

    override suspend fun featured(page: Int): Nothing = error("Unexpected featured request")

    override suspend fun search(query: String, page: Int, filters: FilterSelections): Nothing =
        error("Unexpected search request")

    override fun pages(manga: Manga, chapter: Chapter): Nothing = error("Unexpected page request")

    override suspend fun details(manga: Manga): AppResult<MangaDetails> {
        beforeDetails()
        return AppResult.Success(manga.fixtureDetails(chapters))
    }
}

private fun Manga.fixtureDetails(chapters: List<SavedChapterEntity>) =
    MangaDetails(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = coverUrl,
        description = "Fixture details",
        author = "",
        rating = "",
        status = "Ongoing",
        genres = emptyList(),
        chapters =
            chapters.map {
                Chapter(it.number, it.name, it.url, it.date, isDownloaded = false, isBookmarked = false)
            },
    )

private fun fixtureRegistry(client: MangaSourceClient): SourceRegistry =
    object : SourceRegistry {
        override fun get(api: String) = client.takeIf { it.api == api }

        override fun isConfigBacked(api: String) = api == client.api

        override fun descriptor(api: String): RuntimeSourceDescriptor? = null

        override fun genericDescriptors(): List<RuntimeSourceDescriptor> = emptyList()
    }

private object ImmediateRefreshForeground : ForegroundUpdater {
    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: ForegroundInfo,
    ): ListenableFuture<Void> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(null)
            "App7 immediate test updater, not an Android foreground service"
        }
}

/** Captures/joins actual startWork execution, never substitutes Future cancellation for completion. */
internal class NotificationWorkerRun {
    private val entered = CompletableDeferred<Job>()
    val completion = CompletableDeferred<Throwable?>()
    lateinit var worker: LibraryRefreshWorker
        private set
    lateinit var future: ListenableFuture<ListenableWorker.Result>
        private set
    var mayUseRoom = false
        private set

    suspend fun captureExecution() {
        val actual = startWorkExecutionJob(currentCoroutineContext().job)
        if (entered.complete(actual)) actual.invokeOnCompletion { completion.complete(it) }
    }

    fun start(worker: LibraryRefreshWorker) {
        check(!mayUseRoom)
        this.worker = worker
        mayUseRoom = true
        future = worker.startWork()
    }

    suspend fun execution(): Job = withTimeout(NOTIFICATION_WAIT_MILLIS) { entered.await() }

    fun stopAndCancel(): Boolean {
        // Exact Work2.11.2 awaitWithin order: stop alone does not cancel startWork.
        worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        return future.cancel(false)
    }

    suspend fun join() {
        withTimeout(NOTIFICATION_WAIT_MILLIS) { execution().join() }
        mayUseRoom = false
    }

    suspend fun close() =
        withContext(NonCancellable) {
            if (!mayUseRoom) return@withContext
            if (::worker.isInitialized) worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            if (::future.isInitialized) future.cancel(false)
            val actual = if (entered.isCompleted) entered.await() else null
            if (actual != null) {
                withTimeout(NOTIFICATION_WAIT_MILLIS) { actual.join() }
                mayUseRoom = false
            } else if (::future.isInitialized && future.isDone && !future.isCancelled) {
                // A normal doWork return has already joined its structured children.
                mayUseRoom = false
            }
            check(!mayUseRoom) { "Retain Room: startWork execution was not captured/joined" }
        }
}

/**
 * Work2.11.2 supplies a fresh unparented holder Job to launchFuture. The penultimate ancestor is
 * its execution coroutine; the holder may remain active. No implementation-class names are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun startWorkExecutionJob(current: Job): Job {
    val ancestors = generateSequence(current) { it.parent }.toList()
    check(ancestors.size >= 3) { "Expected the actual nested startWork execution" }
    return ancestors[ancestors.lastIndex - 1].also { execution ->
        check(execution !== current && execution.parent === ancestors.last() && execution.isActive)
    }
}

/** A controlled blocking witness around real small BitmapFactory decode, not native preemption. */
internal class BlockingRefreshCover : NotificationCovers {
    val requests = AtomicInteger()
    val itemEntered = CompletableDeferred<Job>()
    val decodeEntered = CompletableDeferred<Unit>()
    val decodeExited = CompletableDeferred<Unit>()
    private val releaseDecode = CountDownLatch(1)
    private val loader =
        NotificationCoverLoader(notificationCoverCalls(requests = requests), NotificationCoverDecoder(::decode))

    override suspend fun withCover(url: String, canPost: () -> Boolean, post: (Bitmap?) -> Unit) {
        itemEntered.complete(currentCoroutineContext().job)
        loader.withCover(url, canPost, post)
    }

    private fun decode(bytes: ByteArray, offset: Int, length: Int, options: BitmapFactory.Options): Bitmap? {
        if (options.inJustDecodeBounds) return BitmapFactory.decodeByteArray(bytes, offset, length, options)
        decodeEntered.complete(Unit)
        try {
            check(releaseDecode.await(DECODE_RELEASE_SECONDS, TimeUnit.SECONDS)) { "Decode release was not signalled" }
            return BitmapFactory.decodeByteArray(bytes, offset, length, options)
        } finally {
            decodeExited.complete(Unit)
        }
    }

    fun release() = releaseDecode.countDown()
}

/** Only the worker deadline/outcome boundary: no HTTP, decode or permit evidence comes from this double. */
internal class RefreshDeadlineWitness(room: NotificationRoomFixture) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val storageWaiting = CompletableDeferred<Unit>()
    val coverWaiting = CompletableDeferred<Unit>()
    val coverExpired = CompletableDeferred<Unit>()
    val attempts = AtomicInteger()
    val chapters =
        room.chapterInserts {
            val ids = room.db.chapterDao().insertChaptersSafely(it)
            if (attempts.incrementAndGet() == 1) {
                withContext(dispatcher) {
                    storageWaiting.complete(Unit)
                    delay(25_000)
                }
            }
            ids
        }
    val covers =
        object : NotificationCovers {
            override suspend fun withCover(url: String, canPost: () -> Boolean, post: (Bitmap?) -> Unit) {
                withTimeoutOrNull(NotificationCoverLimits.BUDGET_MILLIS) {
                    coverWaiting.complete(Unit)
                    awaitCancellation()
                }
                currentCoroutineContext().ensureActive()
                coverExpired.complete(Unit)
                if (canPost()) post(null)
            }
        }
}
