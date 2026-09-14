package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaException
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.publishPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUUID
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS [BackgroundTransport] backed by a background `NSURLSession`.
 *
 * A single background session (fixed identifier [SESSION_ID]) downloads each page to its own OS-temp
 * file; the [Delegate] moves it atomically into the platform download layout
 * (`<files>/manga/<mangaId>/chapter_<chapterId>/image_<pageIndex>.<ext>`) and reports the outcome to
 * the engine via [TransferListener]. Because it is a *background* session the transfers keep running
 * while the app is suspended, and the OS relaunches the app to deliver completions; the host forwards
 * the relaunch's completion handler via [setSystemCompletionHandler].
 *
 * Per-page identity travels on each task's `taskDescription` (`"<mangaId>|<chapterId>|<pageIndex>"`)
 * so completions are matched back to chapters/pages even after a relaunch (recovered via
 * `getAllTasksWithCompletionHandler`). Every event is traced under the `KiraBgDownload` tag
 * ([BgDownloadLog]) — URL hosts and header *names* only (no tokens/cookies/full headers).
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundTransport(
    private val appFileSystem: AppFileSystem,
    private val mediaInspector: PageMediaInspector,
    private val pageBytePolicy: PageBytePolicy = PageBytePolicy(),
) : BackgroundTransport {

    private var listener: TransferListener? = null
    private var systemCompletionHandler: (() -> Unit)? = null
    private val delegate = Delegate(this)
    // The default URLSession delegate queue is serial. Entries live only until didComplete.
    private val outcomes = mutableMapOf<ULong, PageOutcome>()

    // The ONE background session. iOS persists its tasks across suspension/termination; recreating
    // the SAME identifier on relaunch re-attaches us to receive the pending callbacks.
    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID).apply {
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
        BgDownloadLog.log("session.created", "sessionId" to SESSION_ID, "maxPerHost" to MAX_CONNECTIONS_PER_HOST)
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
        BgDownloadLog.log("session.ensureReady", "sessionId" to SESSION_ID)
    }

    override suspend fun enqueue(requests: List<TransferRequest>) {
        requests.forEach { req ->
            val url = NSURL.URLWithString(req.url)
            if (url == null) {
                BgDownloadLog.warn("task.enqueue.invalidUrl", "chapterId" to req.chapterId, "pageIndex" to req.pageIndex)
                listener?.onPageFailed(req.mangaId, req.chapterId, req.pageIndex, "Invalid URL: ${req.url}")
                return@forEach
            }
            val request = NSMutableURLRequest.requestWithURL(url)
            req.headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
            val task = session.downloadTaskWithRequest(request)
            task.taskDescription = encodeDesc(req.mangaId, req.chapterId, req.pageIndex)
            BgDownloadLog.log(
                "task.enqueued",
                "chapterId" to req.chapterId,
                "mangaId" to req.mangaId,
                "pageIndex" to req.pageIndex,
                "taskId" to task.taskIdentifier,
                "taskDesc" to task.taskDescription,
                "host" to url.host,
                "headerNames" to req.headers.keys.joinToString(","),
            )
            task.resume()
        }
    }

    override suspend fun cancelChapter(chapterId: Long) {
        var cancelled = 0
        allTasks().forEach { task ->
            val d = decodeDesc(task.taskDescription) ?: return@forEach
            if (d.chapterId == chapterId) { task.cancel(); cancelled++ }
        }
        BgDownloadLog.log("task.cancelChapter", "chapterId" to chapterId, "cancelled" to cancelled)
    }

    override suspend fun cancelAll() {
        val tasks = allTasks()
        tasks.forEach { it.cancel() }
        BgDownloadLog.log("task.cancelAll", "cancelled" to tasks.size)
    }

    override suspend fun inFlightPages(chapterId: Long): Set<Int> {
        val out = mutableSetOf<Int>()
        allTasks().forEach { task ->
            val d = decodeDesc(task.taskDescription) ?: return@forEach
            if (d.chapterId == chapterId) out += d.pageIndex
        }
        BgDownloadLog.log("session.getAllTasks", "chapterId" to chapterId, "inFlight" to out.size, "pages" to out.sorted())
        return out
    }

    private suspend fun allTasks(): List<NSURLSessionTask> = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            cont.resume((tasks ?: emptyList<Any?>()).filterIsInstance<NSURLSessionTask>())
        }
    }

    // ---- invoked by the Delegate (on the session's delegate queue) ----

    internal fun handleWroteData(task: NSURLSessionTask, bytesWritten: Long, totalBytesWritten: Long, totalExpected: Long) {
        val d = decodeDesc(task.taskDescription) ?: return
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        if (totalBytesWritten > pageBytePolicy.maxEncodedBytes || totalExpected > pageBytePolicy.maxEncodedBytes) {
            // OS delegate progress is coarse, not a hard bound on URLSession's temporary disk use.
            // Keep this reason even when the terminal NSError is merely NSURLErrorCancelled.
            outcome.failure = PageByteLimitExceeded(pageBytePolicy.maxEncodedBytes, maxOf(totalBytesWritten, totalExpected)).message
            reportFailureOnce(task, d, requireNotNull(outcome.failure))
            task.cancel()
        } else if (totalBytesWritten == bytesWritten) {
            BgDownloadLog.log("task.didWriteData.started", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "bytesExpected" to totalExpected)
        }
    }

    internal fun handleFinishedDownload(task: NSURLSessionDownloadTask, location: NSURL) {
        val d = decodeDesc(task.taskDescription) ?: return
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        if (outcome.reported) return
        outcome.failure?.let { reportFailureOnce(task, d, it); return }
        val response = task.response as? NSHTTPURLResponse
        val status = response?.statusCode?.toInt()
        BgDownloadLog.log(
            "task.didFinishDownloading",
            "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "taskId" to task.taskIdentifier,
            "httpStatus" to status,
        )
        if (status == null || status !in 200..299) {
            BgDownloadLog.warn("task.httpError", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "httpStatus" to status)
            reportFailureOnce(task, d, if (status == null) "Missing HTTP response" else "HTTP $status")
            return
        }
        var ownedTemporary: Path? = null
        val system = appFileSystem.fileSystem()
        try {
            pageBytePolicy.checkDeclaredLength(response.expectedContentLength)
            val locationPath = location.path?.toPath() ?: throw IOException("Missing downloaded file")
            pageBytePolicy.checkFileSize(system.metadata(locationPath).size)
            val directory = appFileSystem.chapterDir(d.mangaId, d.chapterId)
            system.createDirectories(directory)
            val temporary = directory / ".image_${d.pageIndex}-${NSUUID().UUIDString}.partial"
            // Unlike atomicMove, moveItem does not replace a colliding destination. Ownership is
            // acquired only after success, before URLSession may remove its callback-local file.
            if (!NSFileManager.defaultManager.moveItemAtURL(location, NSURL.fileURLWithPath(temporary.toString()), error = null)) {
                throw IOException("Could not retain downloaded page")
            }
            ownedTemporary = temporary
            pageBytePolicy.checkFileSize(system.metadata(temporary).size)
            val metadata = mediaInspector.inspect(temporary).requireValid()
            publishPageSnapshot(system, temporary, d.pageIndex, metadata)
            ownedTemporary = null
            outcome.reported = true
            BgDownloadLog.log("file.move.success", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex)
            listener?.onPageComplete(d.mangaId, d.chapterId, d.pageIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = if (failure is PageByteLimitExceeded || failure is PageMediaException) {
                failure.message ?: "Page validation failed"
            } else "Downloaded page could not be saved"
            reportFailureOnce(task, d, reason)
        } finally {
            ownedTemporary?.let { path ->
                try { system.delete(path, mustExist = false) } catch (_: IOException) { /* No published page was removed. */ }
            }
        }
    }

    internal fun handleCompleted(task: NSURLSessionTask, error: NSError?) {
        val d = decodeDesc(task.taskDescription) ?: return
        try {
            val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
            if (outcome.reported) return
            outcome.failure?.let { reportFailureOnce(task, d, it); return }
            if (error?.code == NSURLErrorCancelled) {
                BgDownloadLog.log("task.didComplete.cancelled", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex)
                return // user/engine cancel, not a byte-budget cancellation
            }
            reportFailureOnce(task, d, error?.localizedDescription ?: "Download completed without a page")
        } finally {
            outcomes.remove(task.taskIdentifier)
        }
    }

    private fun reportFailureOnce(task: NSURLSessionTask, d: Desc, reason: String) {
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        outcome.failure = reason
        if (outcome.reported) return
        outcome.reported = true
        listener?.onPageFailed(d.mangaId, d.chapterId, d.pageIndex, reason)
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

    private fun encodeDesc(mangaId: Long, chapterId: Long, pageIndex: Int): String = "$mangaId|$chapterId|$pageIndex"

    private fun decodeDesc(s: String?): Desc? {
        val parts = s?.split('|') ?: return null
        if (parts.size != 3) return null
        val m = parts[0].toLongOrNull() ?: return null
        val c = parts[1].toLongOrNull() ?: return null
        val p = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Desc(m, c, p)
    }

    private data class Desc(val mangaId: Long, val chapterId: Long, val pageIndex: Int)
    private data class PageOutcome(var failure: String? = null, var reported: Boolean = false)

    private companion object {
        const val SESSION_ID = "me.manga.kira.download.transfers"
        const val MAX_CONNECTIONS_PER_HOST: Long = 4
    }
}

/**
 * `NSURLSessionDownloadDelegate` for [IosBackgroundTransport]. A plain `NSObject` subclass (the
 * ObjC-interop requirement; mirrors the in-repo `WebViewDelegate : NSObject(), WKNavigationDelegateProtocol`
 * pattern). Forwards each callback to the owning transport, which is a Koin singleton living for the
 * whole app — so the transport↔session↔delegate retain cycle is intentional and harmless.
 */
@OptIn(ExperimentalForeignApi::class)
private class Delegate(
    private val transport: IosBackgroundTransport,
) : NSObject(), NSURLSessionDownloadDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        transport.handleFinishedDownload(downloadTask, didFinishDownloadingToURL)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        transport.handleWroteData(downloadTask, didWriteData, totalBytesWritten, totalBytesExpectedToWrite)
    }

    @ObjCSignatureOverride
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        transport.handleCompleted(task, didCompleteWithError)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        transport.handleFinishedEvents()
    }
}
