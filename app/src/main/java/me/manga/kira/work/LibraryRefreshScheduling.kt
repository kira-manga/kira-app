package me.manga.kira.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * M2 (2026-07-03) — Android twin of the iOS 12-hour background library refresh.
 *
 * iOS schedules a `BGAppRefreshTask` with `earliestBeginDate = +12h` (`AppDelegate` →
 * `IosLibraryRefreshBridge`); Android now schedules the SAME job as a WorkManager
 * [PeriodicWorkRequest] driving the existing [LibraryRefreshWorker] (the full Phase 12.x port:
 * batched per-manga chapter re-fetch, Room inserts, per-new-chapter notifications, the
 * `library_last_updated` stamp). Native shipped this periodic request commented out and only the
 * manual pull-to-refresh enqueued the worker — this closes that gap as a deliberate forward step.
 *
 * Safety/idempotency:
 *  - **Distinct unique name** ([PERIODIC_WORK_NAME]) from the manual pull-to-refresh chain
 *    (`LibraryRefreshRepositoryImpl.REFRESH_WORK_NAME = "LibraryRefresh"`, a one-time unique
 *    chain) — the two never REPLACE each other, and WorkManager's unique-periodic guarantee means
 *    at most one periodic instance exists regardless of how often [schedule] runs.
 *  - **[ExistingPeriodicWorkPolicy.UPDATE]** re-applies spec changes (interval/constraints) on
 *    upgrade WITHOUT resetting the period clock, so calling this on every app launch is free.
 *  - **The worker itself is re-entrant-safe**: `supervisorScope` + per-manga catch + 15-min outer
 *    timeout + chapter de-dup by URL on insert, and it already tolerates running while the manual
 *    chain runs (both funnel through the same Room upsert semantics; a concurrent manual run just
 *    means some manga fetch twice — wasteful, never corrupting).
 *  - **Constraints**: CONNECTED (a background refresh without network would only burn the battery
 *    budget to fail per-manga — unlike the MANUAL pull, which stays unconstrained for native
 *    parity so an offline gesture visibly runs+fails) + battery-not-low (matches the spirit of
 *    iOS BGAppRefresh, which the system already throttles on battery pressure).
 *  - **Initial delay = one interval**: the first periodic run lands ~12h after install/first
 *    schedule, mirroring iOS's `earliestBeginDate = +12h` request-not-guarantee semantics and
 *    avoiding a redundant refresh right after launch (the user is looking at fresh data already).
 *
 * Lifecycle note: WorkManager runs the periodic job regardless of app foreground state (that is
 * its purpose); on API 31+ the worker's `setForeground` upgrade is rejected while backgrounded
 * and it already degrades to plain background work (see `LibraryRefreshWorker.doWork`).
 */
object LibraryRefreshScheduling {

    const val PERIODIC_WORK_NAME = "LibraryRefreshPeriodic"

    /** 12h — keep in lockstep with the iOS request in `AppDelegate.scheduleLibraryRefresh()`. */
    val REFRESH_INTERVAL: Duration = 12.hours

    /** Visible for the spec test — the exact request [schedule] enqueues. */
    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<LibraryRefreshWorker>(REFRESH_INTERVAL.toJavaDuration())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(REFRESH_INTERVAL.toJavaDuration())
            .build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest(),
        )
    }
}
