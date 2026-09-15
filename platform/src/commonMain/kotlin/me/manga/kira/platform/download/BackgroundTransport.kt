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
    val attemptToken: String,
)

/**
 * Receives per-page transfer outcomes from a [BackgroundTransport].
 *
 * Callbacks may arrive on an arbitrary thread (the iOS `URLSession` delegate queue) and possibly
 * after an app relaunch the OS performed to deliver background events — so the implementation must
 * marshal to its own scope and treat every callback idempotently (a page may complete more than once
 * across a resume). Returning accepts the handoff; it does not acknowledge asynchronous work.
 * Invoke [onPageComplete]'s or [onPageFailed]'s acknowledgement after immediate durable processing
 * and owned-file disposal, including stale/cancelled outcomes. A storage failure retains the existing
 * recoverable cleanup custody rather than claiming disposal succeeded. Do not wait for retry backoff,
 * future transfers or a whole archive encode. A throwing callback rejects the handoff to the transport.
 */
interface TransferListener {
    /** The receiver owns this private staged page; only its original attempt may publish it. */
    fun onPageComplete(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        page: StagedDownloadPage,
        acknowledge: () -> Unit,
    )

    /** The page transfer failed terminally (after the OS's own transient-error retries). */
    fun onPageFailed(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        message: String?,
        acknowledge: () -> Unit,
    )
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
    suspend fun cancelChapter(chapterId: Long, attemptToken: String)

    /** Cancel every in-flight transfer across all chapters. */
    suspend fun cancelAll()

    /** Page indices currently enqueued/running for [chapterId], recovered from the live session. */
    suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int>

    /** Re-attach to a background session the OS may have relaunched; recovers pending tasks. Idempotent. */
    suspend fun ensureReady()

    /**
     * Store the system completion handler forwarded from the host's
     * `application(_:handleEventsForBackgroundURLSession:completionHandler:)`; the transport invokes
     * it on main after the session has finished delivering this window and every admitted receiver
     * has acknowledged it. Install before session reattachment. Later windows are not joined.
     */
    fun setSystemCompletionHandler(handler: () -> Unit)
}
