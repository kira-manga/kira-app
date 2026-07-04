package me.manga.kira.presentation.features.download.domain.clean

/**
 * Pure target selection for the iOS background engine's **limited resolve-ahead** (owner-approved
 * 2026-07-02). No I/O — fully unit-tested.
 *
 * Why: transfers run out-of-process on the background `URLSession`, but starting chapter N+1
 * needs its page-link RESOLVE (a network scrape) — which needs app CPU. Backgrounded on pre-iOS-26
 * devices that CPU only comes from short opportunistic windows, so a multi-chapter batch stalled
 * after each chapter. With the next few manifests resolved AHEAD of time (while the app has CPU),
 * the brief `handleEventsForBackgroundURLSession` wake at chapter completion only needs the cheap
 * manifest-read + task-enqueue to keep the batch moving — no scrape.
 *
 * Deliberately limited (the owner's constraints, in order of priority):
 *  - **window**: only the next [window] queued chapters in processing order are ever considered —
 *    never the whole batch (a 100-chapter enqueue must not fan out 100 scrapes).
 *  - **one scrape in flight at a time**: a non-empty [prefetching] set selects nothing, so
 *    prefetch scrapes are strictly sequential — combined with the engine's spacing delay and its
 *    pause-on-failure, prefetch can never hammer a source.
 *  - **no duplicate work**: chapters already carrying a manifest, already being resolved for real
 *    ([resolving]), or already being prefetched are skipped.
 *
 * Transfers stay strictly one-chapter-at-a-time (`CHAPTER_CONCURRENCY = 1`) — resolve-ahead
 * prepares manifests only; it never enqueues page transfers for a queued chapter.
 */
object ResolveAheadRules {

    /**
     * The single chapter to prefetch next, or `null` when there is nothing to do. [hasManifest] is
     * probed lazily and only for chapters inside the window that passed the cheaper set checks.
     */
    fun selectNextPrefetch(
        queuedInProcessingOrder: List<Long>,
        window: Int,
        resolving: Set<Long>,
        prefetching: Set<Long>,
        hasManifest: (Long) -> Boolean,
    ): Long? {
        if (window <= 0) return null // disabled
        if (prefetching.isNotEmpty()) return null // serialize: one prefetch scrape at a time
        return queuedInProcessingOrder.asSequence()
            .take(window)
            .filter { it !in resolving && it !in prefetching }
            .firstOrNull { !hasManifest(it) }
    }
}
