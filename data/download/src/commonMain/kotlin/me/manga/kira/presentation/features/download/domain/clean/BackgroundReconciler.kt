package me.manga.kira.presentation.features.download.domain.clean

/**
 * The reconciliation decision for one chapter, computed purely from durable state.
 */
data class ReconcilePlan(
    /** Page indices to (re)enqueue now (missing, not in-flight, retry budget remaining). */
    val toEnqueue: List<Int>,
    /** Every expected page is on disk → the chapter can be finalized (CBZ + bookkeeping). */
    val isComplete: Boolean,
    /** A page exhausted its retry budget while still missing → the chapter must fail. */
    val failedPageIndex: Int?,
)

/**
 * Pure reconciliation for the iOS background-download engine (background-downloads M3).
 *
 * Given a chapter's [DownloadManifest], the page indices currently on disk, the indices with a live
 * background-`URLSession` task, and the retry budget, it decides what to do — with no I/O, so it is
 * fully unit-tested. The engine performs the I/O the plan implies (enqueue / finalize / fail).
 *
 * Rules, in order:
 *  - **Complete**: every manifest page is on disk → finalize (nothing to enqueue).
 *  - **Failed**: some page is missing, not in-flight, and has already failed `>= maxAttempts` times →
 *    the chapter can never complete → fail (reporting the offending page; nothing enqueued).
 *  - **Enqueue**: otherwise, every page that is missing AND not in-flight AND under the retry budget.
 *    In-flight pages are left alone and on-disk pages are skipped — this is the dedupe that prevents
 *    duplicate transfers on rapid pumps / resume.
 */
object BackgroundReconciler {
    fun plan(
        manifest: DownloadManifest,
        pagesOnDisk: Set<Int>,
        inFlightPages: Set<Int>,
        maxAttempts: Int,
    ): ReconcilePlan {
        val missing = manifest.pages.filter { it.index !in pagesOnDisk }
        if (missing.isEmpty()) {
            return ReconcilePlan(toEnqueue = emptyList(), isComplete = true, failedPageIndex = null)
        }
        // A missing, not-in-flight page that has exhausted its retries dooms the chapter. (A page that
        // is over budget but still in-flight is left to finish — we don't fail while a task is live.)
        val exhausted = missing.firstOrNull { it.index !in inFlightPages && it.attempts >= maxAttempts }
        if (exhausted != null) {
            return ReconcilePlan(toEnqueue = emptyList(), isComplete = false, failedPageIndex = exhausted.index)
        }
        val toEnqueue = missing
            .filter { it.index !in inFlightPages && it.attempts < maxAttempts }
            .map { it.index }
        return ReconcilePlan(toEnqueue = toEnqueue, isComplete = false, failedPageIndex = null)
    }
}
