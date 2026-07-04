package me.manga.kira.platform.download

/**
 * A single page transfer the background-download engine wants performed **durably** — on iOS it
 * survives app suspension/termination via a background `NSURLSession`. Identified by
 * (mangaId, chapterId, pageIndex); the transport writes the bytes to the platform download layout
 * (`<files>/manga/<mangaId>/chapter_<chapterId>/image_<pageIndex>.<ext>`) and reports the outcome.
 */
data class TransferRequest(
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Receives per-page transfer outcomes from a [BackgroundTransport].
 *
 * Callbacks may arrive on an arbitrary thread (the iOS `URLSession` delegate queue) and possibly
 * after an app relaunch the OS performed to deliver background events — so the implementation must
 * marshal to its own scope and treat every callback idempotently (a page may complete more than once
 * across a resume).
 */
interface TransferListener {
    /** The page's bytes are now on disk at the platform download path for (chapterId, pageIndex). */
    fun onPageComplete(mangaId: Long, chapterId: Long, pageIndex: Int)

    /** The page transfer failed terminally (after the OS's own transient-error retries). */
    fun onPageFailed(mangaId: Long, chapterId: Long, pageIndex: Int, message: String?)
}

/**
 * Durable, OS-managed file-transfer port for background downloads.
 *
 * The iOS implementation (`IosBackgroundTransport`) wraps a background `NSURLSession`: enqueued
 * transfers keep running while the app is suspended or terminated, and the OS relaunches the app to
 * deliver completions. Desktop/Android do not use this port (their engines own their own transfer
 * mechanism — the in-process coroutine queue / WorkManager), so no implementation is bound there.
 *
 * Lifecycle: [setListener] once, [ensureReady] on every app launch (re-attaches to a session the OS
 * may have relaunched and recovers in-flight tasks), then [enqueue] requests as the engine prepares
 * its rolling window.
 */
interface BackgroundTransport {
    fun setListener(listener: TransferListener)

    suspend fun enqueue(requests: List<TransferRequest>)

    /** Cancel every in-flight transfer for [chapterId] (e.g. the user cancelled the chapter). */
    suspend fun cancelChapter(chapterId: Long)

    /** Cancel every in-flight transfer across all chapters. */
    suspend fun cancelAll()

    /** Page indices currently enqueued/running for [chapterId], recovered from the live session. */
    suspend fun inFlightPages(chapterId: Long): Set<Int>

    /** Re-attach to a background session the OS may have relaunched; recovers pending tasks. Idempotent. */
    suspend fun ensureReady()

    /**
     * Store the system completion handler forwarded from the host's
     * `application(_:handleEventsForBackgroundURLSession:completionHandler:)`; the transport invokes
     * it once the session reports it has finished delivering its queued background events.
     */
    fun setSystemCompletionHandler(handler: () -> Unit)
}
