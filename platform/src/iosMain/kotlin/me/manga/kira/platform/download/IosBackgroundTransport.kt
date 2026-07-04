package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.suspendCancellableCoroutine
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
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
) : BackgroundTransport {

    private var listener: TransferListener? = null
    private var systemCompletionHandler: (() -> Unit)? = null
    private val delegate = Delegate(this)

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
        // First callback only (totalBytesWritten == this chunk) → "the transfer is moving bytes".
        // Per-byte logging would be far too noisy even for the verbose test build.
        if (totalBytesWritten != bytesWritten) return
        val d = decodeDesc(task.taskDescription) ?: return
        BgDownloadLog.log("task.didWriteData.started", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "bytesExpected" to totalExpected)
    }

    internal fun handleFinishedDownload(task: NSURLSessionDownloadTask, location: NSURL) {
        val d = decodeDesc(task.taskDescription) ?: return
        val response = task.response as? NSHTTPURLResponse
        val status = response?.statusCode?.toInt() ?: 200
        BgDownloadLog.log(
            "task.didFinishDownloading",
            "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "taskId" to task.taskIdentifier,
            "httpStatus" to status, "tempPath" to location.path,
        )
        if (status !in 200..299) {
            BgDownloadLog.warn("task.httpError", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "httpStatus" to status)
            listener?.onPageFailed(d.mangaId, d.chapterId, d.pageIndex, "HTTP $status")
            return
        }
        // Detect the extension from the post-redirect URL (currentRequest) — a 30x to a different
        // path/ext (e.g. a .php endpoint redirecting to a .webp CDN) would otherwise be labelled from
        // the pre-redirect originalRequest. Falls back to originalRequest if currentRequest is absent.
        val ext = detectExtension(
            task.currentRequest?.URL?.absoluteString ?: task.originalRequest?.URL?.absoluteString,
            response?.valueForHTTPHeaderField("Content-Type"),
        )
        val dirPath = appFileSystem.chapterDir(d.mangaId, d.chapterId).toString()
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(dirPath, withIntermediateDirectories = true, attributes = null, error = null)
        val destPath = "$dirPath/image_${d.pageIndex}.$ext"
        // Atomic-ish: remove any prior file for this page index then move the OS temp file into place
        // (same app-container volume on iOS, so moveItem is effectively a rename). A failed/partial
        // transfer never reaches here (the OS only calls didFinishDownloading on success), so a final
        // image_* file is complete. Remove by the `image_<index>.` PREFIX, not just the exact destPath:
        // a retry that detects a different ext (image_5.jpg now arriving as image_5.webp) would otherwise
        // leave BOTH on disk, and onDiskPagePaths globs image_<index>.* → the page would encode twice
        // into the CBZ. The "." after the index keeps image_5. from matching image_50.*.
        val pagePrefix = "image_${d.pageIndex}."
        (fm.contentsOfDirectoryAtPath(dirPath, error = null))?.forEach { entry ->
            val name = entry as? String ?: return@forEach
            if (name.startsWith(pagePrefix)) fm.removeItemAtPath("$dirPath/$name", error = null)
        }
        val moved = fm.moveItemAtURL(location, toURL = NSURL.fileURLWithPath(destPath), error = null)
        if (moved) {
            BgDownloadLog.log("file.move.success", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "finalPath" to destPath)
            listener?.onPageComplete(d.mangaId, d.chapterId, d.pageIndex)
        } else {
            BgDownloadLog.error(null, "file.move.failure", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "destPath" to destPath)
            listener?.onPageFailed(d.mangaId, d.chapterId, d.pageIndex, "move failed -> $destPath")
        }
    }

    internal fun handleCompleted(task: NSURLSessionTask, error: NSError?) {
        if (error == null) return // success path handled in handleFinishedDownload
        val d = decodeDesc(task.taskDescription) ?: return
        if (error.code == NSURLErrorCancelled) {
            BgDownloadLog.log("task.didComplete.cancelled", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex)
            return // user/engine cancel — not a reportable failure
        }
        BgDownloadLog.warn(
            "task.didCompleteWithError",
            "chapterId" to d.chapterId, "pageIndex" to d.pageIndex, "errorCode" to error.code, "msg" to error.localizedDescription,
        )
        listener?.onPageFailed(d.mangaId, d.chapterId, d.pageIndex, error.localizedDescription)
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

    private fun detectExtension(urlString: String?, contentType: String?): String {
        val urlExt = urlString?.substringAfterLast('.', "")?.substringBefore('?')?.lowercase().orEmpty()
        if (urlExt in IMAGE_EXTENSIONS) return urlExt
        val ct = contentType?.lowercase().orEmpty()
        return when {
            "avif" in ct -> "avif"
            "jpeg" in ct || "jpg" in ct -> "jpg"
            "png" in ct -> "png"
            "gif" in ct -> "gif"
            "webp" in ct -> "webp"
            "bmp" in ct -> "bmp"
            else -> "jpg"
        }
    }

    private fun encodeDesc(mangaId: Long, chapterId: Long, pageIndex: Int): String = "$mangaId|$chapterId|$pageIndex"

    private fun decodeDesc(s: String?): Desc? {
        val parts = s?.split('|') ?: return null
        if (parts.size != 3) return null
        val m = parts[0].toLongOrNull() ?: return null
        val c = parts[1].toLongOrNull() ?: return null
        val p = parts[2].toIntOrNull() ?: return null
        return Desc(m, c, p)
    }

    private data class Desc(val mangaId: Long, val chapterId: Long, val pageIndex: Int)

    private companion object {
        const val SESSION_ID = "me.manga.kira.download.transfers"
        const val MAX_CONNECTIONS_PER_HOST: Long = 4
        val IMAGE_EXTENSIONS = setOf("avif", "jpg", "jpeg", "png", "gif", "webp", "bmp")
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
