package me.manga.kira

/**
 * Process-wide once-guard for `App()`'s one-shot startup tasks (download reconcile, config
 * refresh + catalog sync, app_open analytics).
 *
 * On Android the whole `App()` composition is rebuilt on every Activity recreation (rotation,
 * theme change, resize) because `MainActivity` pins no `configChanges`, so a plain
 * `LaunchedEffect(Unit)` re-fired the "once per launch" tasks on each rotation (2026-07 audit).
 * The worst effect was the download reconciler's `reEnqueueInterrupted`: it treats RUNNING rows as
 * orphans of a killed process and resets them to QUEUED/progress=0 — correct at process start,
 * destructive mid-download on a rotation. The same re-run also duplicated the `app_open` analytics
 * event and repeated the config refresh + catalog sync (with its stored-URL sweeps).
 *
 * The guard is process-scoped: a fresh process gets one `true` (the reconciler MUST run then —
 * that is its whole purpose), every later composition gets `false`. Main-thread only (the
 * composition applier), so a plain Boolean is race-free. iOS/Desktop build their composition once
 * per process, so this is inert there.
 *
 * Accepted trade-off: a rotation within the sub-second startup window cancels the in-flight tasks
 * (LaunchedEffect disposal) without re-claiming — deliberately, because re-claiming would let the
 * reconciler re-run after downloads have started, the very hazard this guard removes. All tasks
 * are best-effort and self-heal on the next process launch.
 */
internal object StartupTasksOnce {
    private var claimed = false

    /** True exactly once per process; later calls (Activity recreations) get false. */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }

    /** Test hook — resets the guard so each test starts from a fresh-process state. */
    internal fun resetForTests() {
        claimed = false
    }
}
