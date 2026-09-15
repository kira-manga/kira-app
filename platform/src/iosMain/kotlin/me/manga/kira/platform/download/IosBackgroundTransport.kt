package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS [BackgroundTransport] backed by a background `NSURLSession`.
 *
 * A single background session (fixed identifier [SESSION_ID]) downloads each page to its own OS-temp
 * file; the [IosBackgroundSessionDelegate] moves it atomically into the platform download layout
 * (`<files>/manga/<mangaId>/chapter_<chapterId>/image_<pageIndex>.<ext>`) and reports the outcome to
 * the engine via [TransferListener]. Because it is a *background* session the transfers keep running
 * while the app is suspended, and the OS relaunches the app to deliver completions; the host forwards
 * the relaunch's completion handler via [setSystemCompletionHandler].
 *
 * Per-page identity travels on each task's `taskDescription` (`"<mangaId>|<chapterId>|<pageIndex>"`)
 * so completions are matched back to chapters/pages even after a relaunch (recovered via
 * `getAllTasksWithCompletionHandler`). Every event is traced under the `KiraBgDownload` tag
 * ([BgDownloadLog]) — numeric local identifiers/status only, never URL/header/path or error text.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundTransport(
    appFileSystem: AppFileSystem,
    mediaInspector: PageMediaInspector,
    pageBytePolicy: PageBytePolicy = PageBytePolicy(),
) : BackgroundTransport {
    private var listener: TransferListener? = null
    private var systemCompletionHandler: (() -> Unit)? = null
    private val delegate = IosBackgroundSessionDelegate(this)
    private val readiness = Mutex()
    private var legacyTasksFenced = false

    private val callbacks = IosPageTransferCallbacks(appFileSystem, mediaInspector, pageBytePolicy) { listener }

    // The ONE background session. iOS persists its tasks across suspension/termination; recreating
    // the SAME identifier on relaunch re-attaches us to receive the pending callbacks.
    private val session: NSURLSession by lazy {
        val config =
            NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID).apply {
                sessionSendsLaunchEvents = true
                discretionary = false
                HTTPMaximumConnectionsPerHost = MAX_CONNECTIONS_PER_HOST
                // DELIBERATELY no timeoutIntervalForResource override (OS default ≈ 7 days). The
                // resource clock keeps running while a task merely WAITS for connectivity, so any
                // tighter bound would expire offline-queued downloads (queue on airplane mode, come
                // online hours later) — owner decision 2026-07-02. Accepted cost: a genuinely
                // stalled-but-alive page can pin its chapter RUNNING for a long time. Follow-up after
                // device QA: an engine-side PROGRESS-STALL watchdog (no didWriteData/completion
                // movement for N minutes while connectivity is up → cancel + re-enqueue that page)
                // instead of a wall-clock resource cap.
            }
        BgDownloadLog.log("session.created", "maxPerHost" to MAX_CONNECTIONS_PER_HOST)
        NSURLSession.sessionWithConfiguration(config, delegate = delegate, delegateQueue = null)
    }

    override fun setListener(listener: TransferListener) {
        this.listener = listener
    }

    override fun setSystemCompletionHandler(handler: () -> Unit) {
        BgDownloadLog.log("session.completionHandler.received")
        this.systemCompletionHandler = handler
    }

    override suspend fun ensureReady() {
        // Touch the lazy session so the delegate is attached and the OS can deliver pending events.
        session
        // Tokenless/v1 tasks have no publication authority after upgrade. Never adopt their callbacks.
        readiness.withLock {
            if (!legacyTasksFenced) {
                allTasks().filter { IosTransferIdentity.decode(it.taskDescription) == null }.forEach { it.cancel() }
                legacyTasksFenced = true
            }
        }
        BgDownloadLog.log("session.ensureReady")
    }

    override suspend fun enqueue(requests: List<TransferRequest>) {
        requests.forEach { enqueueRequest(it) }
    }

    private fun enqueueRequest(req: TransferRequest) {
        val url = NSURL.URLWithString(req.url)
        if (url == null) {
            BgDownloadLog.warn("task.enqueue.invalidUrl", "chapterId" to req.chapterId, "pageIndex" to req.pageIndex)
            listener?.onPageFailed(req.mangaId, req.chapterId, req.pageIndex, req.attemptToken, "Invalid URL: ${req.url}")
            return
        }
        val request = NSMutableURLRequest.requestWithURL(url)
        req.headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        val task = session.downloadTaskWithRequest(request)
        task.taskDescription = IosTransferIdentity(req.mangaId, req.chapterId, req.pageIndex, req.attemptToken).encode()
        logEnqueued(req, url, task)
        task.resume()
    }

    private fun logEnqueued(
        req: TransferRequest,
        url: NSURL,
        task: NSURLSessionTask,
    ) {
        BgDownloadLog.log(
            "task.enqueued",
            "chapterId" to req.chapterId,
            "mangaId" to req.mangaId,
            "pageIndex" to req.pageIndex,
            "taskId" to task.taskIdentifier,
        )
    }

    override suspend fun cancelChapter(chapterId: Long, attemptToken: String) {
        var cancelled = 0
        allTasks().forEach { task ->
            val d = IosTransferIdentity.decode(task.taskDescription) ?: return@forEach
            if (d.chapterId == chapterId && d.attemptToken == attemptToken) {
                task.cancel()
                cancelled++
            }
        }
        BgDownloadLog.log("task.cancelChapter", "chapterId" to chapterId, "cancelled" to cancelled)
    }

    override suspend fun cancelAll() {
        val tasks = allTasks()
        tasks.forEach { it.cancel() }
        BgDownloadLog.log("task.cancelAll", "cancelled" to tasks.size)
    }

    override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> {
        val out = mutableSetOf<Int>()
        allTasks().forEach { task ->
            val d = IosTransferIdentity.decode(task.taskDescription) ?: return@forEach
            if (d.chapterId == chapterId && d.attemptToken == attemptToken) out += d.pageIndex
        }
        BgDownloadLog.log(
            "session.getAllTasks",
            "chapterId" to chapterId,
            "inFlight" to out.size,
        )
        return out
    }

    private suspend fun allTasks(): List<NSURLSessionTask> =
        suspendCancellableCoroutine { cont ->
            session.getAllTasksWithCompletionHandler { tasks ->
                cont.resume((tasks ?: emptyList<Any?>()).filterIsInstance<NSURLSessionTask>())
            }
        }

    // ---- invoked by the delegate (on the session's serial delegate queue) ----

    internal fun handleWroteData(
        task: NSURLSessionTask,
        bytesWritten: Long,
        totalBytesWritten: Long,
        totalExpected: Long,
    ) {
        callbacks.handleWroteData(task, bytesWritten, totalBytesWritten, totalExpected)
    }

    internal fun handleFinishedDownload(
        task: NSURLSessionDownloadTask,
        location: NSURL,
        // Keep the handler testable with suspended native tasks; the delegate uses task.response.
        response: NSHTTPURLResponse? = task.response as? NSHTTPURLResponse,
    ) {
        callbacks.handleFinishedDownload(task, location, response)
    }

    internal fun handleCompleted(
        task: NSURLSessionTask,
        error: NSError?,
    ) {
        callbacks.handleCompleted(task, error)
    }

    internal fun handleFinishedEvents() {
        BgDownloadLog.log("session.didFinishEvents")
        val handler = systemCompletionHandler
        systemCompletionHandler = null
        if (handler != null) {
            // Apple's background-session contract: the completion handler captured from
            // `application(_:handleEventsForBackgroundURLSession:completionHandler:)` must be invoked
            // on the MAIN thread (it triggers the UI-snapshot/suspend bookkeeping). This callback
            // arrives on the session's delegate queue, so hop explicitly.
            dispatch_async(dispatch_get_main_queue()) {
                BgDownloadLog.log("session.completionHandler.invoked")
                handler.invoke()
            }
        }
    }

    private companion object {
        const val SESSION_ID = "me.manga.kira.download.transfers"
        const val MAX_CONNECTIONS_PER_HOST: Long = 4
    }
}
