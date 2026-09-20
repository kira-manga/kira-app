package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue

/**
 * Durable background transfers with one graph-owned exclusion and serial same-identifier sessions.
 * The initial reservation precedes lazy attachment; each session keeps it through actual native
 * invalidation, and task/event children retain terminal and receiver cleanup independently.
 * Cancellation only requests native cancellation. [IosBackgroundEventDrain] still owns exactly one
 * delivered system-completion window; it is not the session/task lifetime barrier.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundTransport(
    appFileSystem: AppFileSystem,
    mediaInspector: PageMediaInspector,
    recovery: DownloadOperationExclusion.Recovery,
    pageBytePolicy: PageBytePolicy = PageBytePolicy(),
) : BackgroundTransport {
    private val operations = recovery.exclusion
    private val initialLifetime = IosNativeSessionLifetime(recovery.takeOperation())
    private var listener: TransferListener? = null
    private val eventDrain = IosBackgroundEventDrain()
    private val callbacks =
        IosPageTransferCallbacks(appFileSystem, mediaInspector, pageBytePolicy, { listener }, ::admitEvent)
    private val sessions = IosBackgroundSessions(initialLifetime, ::prepareStaging) { lifetime ->
        IosBackgroundSessionDelegate(this, lifetime)
    }

    /** Initial native ownership is already reserved, including direct suspended-task handler tests. */
    internal fun prepareStaging() = callbacks.prepareStaging()

    override fun setListener(listener: TransferListener) {
        this.listener = listener
    }

    override fun setSystemCompletionHandler(handler: () -> Unit) {
        BgDownloadLog.log("session.completionHandler.received")
        eventDrain.setCompletionHandler(handler)
    }

    override suspend fun ensureReady() = operations.withOperation { operation ->
        sessions.ensureReady(operation)
        BgDownloadLog.log("session.ensureReady")
    }

    override suspend fun enqueue(requests: List<TransferRequest>) = operations.withOperation { operation ->
        val valid = requests.mapNotNull { request ->
            val url = NSURL.URLWithString(request.url)
            if (url == null) {
                reportInvalidUrl(request, operation)
                null
            } else request to url
        }
        if (valid.isNotEmpty()) sessions.withSession(operation) { session, lifetime ->
            valid.forEach { (request, url) -> enqueueRequest(request, url, session, lifetime, operation) }
        }
    }

    private fun enqueueRequest(
        req: TransferRequest,
        url: NSURL,
        session: NSURLSession,
        lifetime: IosNativeSessionLifetime,
        operation: DownloadOperationExclusion.Operation,
    ) {
        val request = NSMutableURLRequest.requestWithURL(url)
        req.headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        val nativeOwnership = operation.retain() // Before native task creation, not merely before resume.
        val task = try {
            session.downloadTaskWithRequest(request)
        } catch (failure: Throwable) {
            nativeOwnership.release()
            throw failure
        }
        lifetime.registerCreatedTask(task, nativeOwnership)
        try {
            task.taskDescription = IosTransferIdentity(req.mangaId, req.chapterId, req.pageIndex, req.attemptToken).encode()
            logEnqueued(req, task)
            task.resume()
        } catch (failure: Throwable) {
            task.cancel() // The registered handle remains until real terminal delivery.
            throw failure
        }
    }

    private fun reportInvalidUrl(req: TransferRequest, operation: DownloadOperationExclusion.Operation) {
        BgDownloadLog.warn("task.enqueue.invalidUrl", "chapterId" to req.chapterId, "pageIndex" to req.pageIndex)
        val event = IosTransferEvent(operation, admitEvent())
        try {
            val receiver = listener ?: return
            event.deliver { callbackOperation, acknowledge ->
                receiver.onPageFailed(
                    req.mangaId, req.chapterId, req.pageIndex, req.attemptToken, "Invalid download URL",
                    callbackOperation, acknowledge,
                )
            }
        } finally {
            event.finishDelivery()
        }
    }

    private fun logEnqueued(req: TransferRequest, task: NSURLSessionTask) {
        BgDownloadLog.log(
            "task.enqueued",
            "chapterId" to req.chapterId,
            "mangaId" to req.mangaId,
            "pageIndex" to req.pageIndex,
            "taskId" to task.taskIdentifier,
        )
    }

    override suspend fun cancelChapter(chapterId: Long, attemptToken: String) = operations.withOperation { operation ->
        var cancelled = 0
        sessions.tasks(operation).forEach { task ->
            val identity = IosTransferIdentity.decode(task.taskDescription) ?: return@forEach
            if (identity.chapterId == chapterId && identity.attemptToken == attemptToken) {
                task.cancel()
                cancelled++
            }
        }
        BgDownloadLog.log("task.cancelChapter", "chapterId" to chapterId, "cancelled" to cancelled)
    }

    override suspend fun cancelAll() = operations.withOperation { operation ->
        val tasks = sessions.tasks(operation)
        tasks.forEach { it.cancel() }
        BgDownloadLog.log("task.cancelAll", "cancelled" to tasks.size)
    }

    override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> =
        operations.withOperation { operation ->
            val pages = sessions.tasks(operation).mapNotNull { task ->
                IosTransferIdentity.decode(task.taskDescription)?.takeIf {
                    it.chapterId == chapterId && it.attemptToken == attemptToken
                }?.pageIndex
            }.toSet()
            BgDownloadLog.log("session.getAllTasks", "chapterId" to chapterId, "inFlight" to pages.size)
            pages
        }

    internal fun admitEvent(): () -> Unit = eventDrain.admitEvent()

    // Default initial lifetime preserves the existing suspended-native-task handler seams. Actual
    // delegates always pass their own generation, so a late old callback cannot borrow a successor.
    internal fun handleWroteData(
        task: NSURLSessionTask,
        bytesWritten: Long,
        totalBytesWritten: Long,
        totalExpected: Long,
        lifetime: IosNativeSessionLifetime = initialLifetime,
    ) = withCallback(lifetime, task) { operation ->
        callbacks.handleWroteData(task, bytesWritten, totalBytesWritten, totalExpected, operation)
    }

    internal fun handleFinishedDownload(
        task: NSURLSessionDownloadTask,
        location: NSURL,
        response: NSHTTPURLResponse? = task.response as? NSHTTPURLResponse,
        lifetime: IosNativeSessionLifetime = initialLifetime,
    ) = withCallback(lifetime, task) { operation ->
        callbacks.handleFinishedDownload(task, location, response, operation)
    }

    internal fun handleCompleted(
        task: NSURLSessionTask,
        error: NSError?,
        lifetime: IosNativeSessionLifetime = initialLifetime,
    ) = withCallback(lifetime, task, terminal = true) { operation ->
        callbacks.handleCompleted(task, error, operation)
    }

    private inline fun withCallback(
        lifetime: IosNativeSessionLifetime,
        task: NSURLSessionTask,
        terminal: Boolean = false,
        action: (DownloadOperationExclusion.Operation) -> Unit,
    ) {
        val operation = lifetime.beginCallback(task, terminal) ?: return
        try {
            action(operation)
        } finally {
            operation.release()
            if (terminal) lifetime.finishTask(task)
        }
    }

    internal fun handleFinishedEvents() {
        BgDownloadLog.log("session.didFinishEvents")
        eventDrain.finishEvents()
    }
}
