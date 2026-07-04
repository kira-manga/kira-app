package me.manga.kira.platform.background

/**
 * Platform abstraction for extending execution while the app is backgrounded.
 *
 * Wraps a unit of work so the OS keeps the process alive long enough to finish it after the user
 * leaves the app:
 * - **iOS** maps [runGuarded] to a `UIApplication` background-task assertion — a *bounded* grace
 *   period (~30s), not continuous background downloading. See `IOS_BACKGROUND_DOWNLOADS.md` for the
 *   limits and the path to a real background `URLSession` transport. The download queue is
 *   persisted in Room, so anything not finished within the grace period resumes on next launch.
 * - **Desktop** binds [PassThrough] (the JVM keeps running while minimized).
 * - **Android** is unaffected — downloads run under WorkManager.
 */
interface BackgroundExecutionGuard {

    /**
     * Runs [block] while holding a best-effort background-execution assertion, releasing it when
     * [block] returns or throws. Never alters [block]'s result or exception; if no assertion can be
     * acquired, [block] still runs (just without the grace period).
     */
    suspend fun <T> runGuarded(label: String, block: suspend () -> T): T

    /** Pass-through binding (Desktop): no assertion needed. */
    object PassThrough : BackgroundExecutionGuard {
        override suspend fun <T> runGuarded(label: String, block: suspend () -> T): T = block()
    }
}
