package me.manga.kira.platform.notification

/**
 * Cross-platform surface for **download-progress** system notifications.
 *
 * Distinct from [NotificationPresenter] (the generic title/body facade): this carries the
 * download lifecycle so each platform impl can pick the right presentation.
 *
 * - **iOS** delivers [onProgress] *silently* (Notification Center / list only — no banner, no
 *   sound) and fires a banner + sound on [onComplete] / [onFailed]. [key] is the chapter id, so
 *   re-calling [onProgress] with the same key updates the same notification in place.
 * - **Desktop** binds [NoOp] (the owner opted iOS-only; avoids per-page tray-balloon spam).
 * - **Android** is unaffected — it shows download notifications via its WorkManager foreground
 *   worker (`DownloadWorkerV2`), not through this facade.
 *
 * All ops are suspending so impls can hop dispatchers.
 */
interface DownloadNotifier {

    /** Silent, in-place progress update for a chapter (body is "$current/$total pages"). */
    suspend fun onProgress(key: Int, title: String, current: Int, total: Int)

    /**
     * Silent, in-place "finalizing" update: all pages are downloaded but the durable artifact (the CBZ)
     * is still being built (or is waiting for a CPU window to build). Presented exactly like
     * [onProgress] — no banner, no sound — so the user sees the chapter is being processed, not stuck.
     * Replaces the in-place notification for [key]. The alerting [onComplete] must fire **only** once
     * the artifact is actually ready. Default no-op for platforms that don't surface these (Desktop).
     */
    suspend fun onFinalizing(key: Int, title: String) {}

    /** Alerting completion notice (banner + sound) — post ONLY when the chapter is truly ready to read. */
    suspend fun onComplete(key: Int, title: String)

    /** Alerting failure notice (banner + sound) for a failed chapter. */
    suspend fun onFailed(key: Int, title: String)

    /** Remove any pending/delivered notification for [key] (e.g. on user cancel). */
    suspend fun clear(key: Int)

    /** No-op binding for platforms that don't surface download notifications (Desktop). */
    object NoOp : DownloadNotifier {
        override suspend fun onProgress(key: Int, title: String, current: Int, total: Int) {}
        override suspend fun onFinalizing(key: Int, title: String) {}
        override suspend fun onComplete(key: Int, title: String) {}
        override suspend fun onFailed(key: Int, title: String) {}
        override suspend fun clear(key: Int) {}
    }
}
