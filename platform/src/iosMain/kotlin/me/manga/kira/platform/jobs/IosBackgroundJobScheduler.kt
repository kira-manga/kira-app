package me.manga.kira.platform.jobs

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS actual for [BackgroundJobScheduler].
 *
 * iOS does not allow arbitrary application-controlled execution of background jobs. The closest
 * analogue is `BGTaskScheduler` with `BGAppRefreshTaskRequest` / `BGProcessingTaskRequest`, but
 * those have hard constraints:
 *  - Each task identifier must be declared in `Info.plist` at build time.
 *  - The system chooses when (and whether) to run the task.
 *  - One-off jobs with arbitrary payloads are not supported.
 *
 * For now we log + no-op and return a UUID so calling code stays uniform across targets. The
 * actual `BGTaskScheduler` integration is deferred to a later iOS app phase.
 *
 * TODO(later iOS phase): wire `BGTaskScheduler` for `library-refresh`. Requires Info.plist task
 *      identifiers and a register-handler call at the iOS app entry point.
 */
class IosBackgroundJobScheduler : BackgroundJobScheduler {

    private val log = Logger.withTag(TAG)

    override fun scheduleOneOff(job: BackgroundJob): String {
        val id = NSUUID().UUIDString
        log.w {
            "scheduleOneOff(tag=${job.tag}) — iOS BGTaskScheduler integration deferred; returning id=$id"
        }
        // Touch NSDate so the import is not dead — the future BGTaskScheduler impl will use this
        // for `earliestBeginDate` on BGAppRefreshTaskRequest.
        @Suppress("UNUSED_VARIABLE")
        val earliest = NSDate.dateWithTimeIntervalSinceNow(job.initialDelayMs / MS_PER_SECOND_D)
        return id
    }

    override fun schedulePeriodic(job: BackgroundJob, intervalMinutes: Long): String {
        val id = NSUUID().UUIDString
        log.w {
            "schedulePeriodic(tag=${job.tag}, interval=${intervalMinutes}m) — iOS " +
                "BGTaskScheduler integration deferred; returning id=$id"
        }
        return id
    }

    override fun cancel(jobId: String) {
        log.d { "cancel($jobId) — no-op on iOS until BGTaskScheduler integration lands" }
    }

    override fun cancelAll() {
        log.d { "cancelAll() — no-op on iOS until BGTaskScheduler integration lands" }
    }

    /**
     * iOS placeholder — emits `JobState.Idle` once. Real per-job tracking is not possible until
     * the BGTaskScheduler integration lands (the system controls task execution, so we'd need to
     * surface `BGTask.expirationHandler` / completion callbacks to feed this flow).
     */
    override fun observeJobState(jobId: String): Flow<JobState> = flowOf(JobState.Idle)

    private companion object {
        const val TAG = "BackgroundJobScheduler"
        const val MS_PER_SECOND_D: Double = 1_000.0
    }
}

/*
 * §253 audit-trail postscript — cluster273 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-BOUND-VIA-LEGACY-EXPECT (rework concrete NOT-YET-WIRED).
 *
 * UNIT KIND: platform-facade — iOS actual of the Phase 5.y.4 BackgroundJobScheduler
 * relocation. Implements the rework plain-interface SPI declared at
 * platform/src/commonMain/.../platform/jobs/BackgroundJobScheduler.kt:22 (expect-decl
 * already swept in clusters 144-149; the commonMain postscript explicitly marks the iOS
 * leg "actual integration deferred" / UNREALIZED).
 *
 * LIVE evidence:
 *  - The rework interface BackgroundJobScheduler IS LIVE: IosBackgroundJobScheduler:26
 *    declares it as its supertype.
 *  - The CONCRETE class IosBackgroundJobScheduler is NOT bound in any rework Koin module:
 *    a repo-wide grep for the three impl class names returns ZERO "single { ... }" sites.
 *  - The ONLY LIVE scheduler Koin binding today is the LEGACY :shared expect class at
 *    shared/src/iosMain/.../di/PlatformModule.ios.kt:94 (single { BackgroundJobScheduler() }),
 *    consumed transitively by data/src/commonMain/.../repository/LibraryRefreshRepositoryImpl.kt:89
 *    (imports the LEGACY me.manga.kira.core.jobs.BackgroundJobScheduler at line 10). On iOS
 *    that legacy actual is itself a log-noop, so the rework runtime behaviour is already mirrored.
 *  - Binding swap to this rework actual is deferred to the Phase 11 / later iOS-app phase that
 *    lands BGTaskScheduler — see the TODO at lines 23-24 of this file.
 * Status: FULFILLED-PORT (relocation landed) but a documented log-noop placeholder, not wired.
 *
 * Delta-axes (this iOS actual's distinct approach):
 *  1. Platform API: NONE actually scheduled. The intended analogue is BGTaskScheduler with
 *     BGAppRefreshTaskRequest / BGProcessingTaskRequest, blocked by Info.plist task-identifier
 *     declarations and the system-controlled execution model (lines 13-21). Only NSUUID,
 *     NSDate.dateWithTimeIntervalSinceNow are touched (the latter solely to keep the import live).
 *  2. Threading/dispatcher: none — every method is a synchronous log-then-return with no
 *     executor, no coroutine, no Worker; calls return immediately on the caller thread.
 *  3. Error handling: there is no failure path to handle — scheduleOneOff / schedulePeriodic
 *     return a fresh NSUUID().UUIDString with a w-log; cancel / cancelAll are d-log no-ops.
 *  4. DI binding mechanism: no-arg constructor; intended future binding
 *     single { IosBackgroundJobScheduler() } is currently absent.
 *  5. 3-actual contract parity: all five interface methods overridden, satisfying the contract
 *     structurally. UNLIKE the Android sibling (real WorkManager lifecycle) and even the Desktop
 *     sibling (real ScheduledExecutorService dispatch), iOS performs NO work and observeJobState
 *     emits flowOf(JobState.Idle) once — the weakest of the three actuals, intentionally, until
 *     the deferred BGTaskScheduler integration lands.
 * Nested-comment hazard check: this file has 2 legitimate KDoc openers (class-doc line 10,
 * observeJobState doc line 59). The appended block is balanced — exactly one opener
 * (slash-star), one closer (star-slash), and zero interior comment delimiters anywhere in
 * the prose (no slash-star, no star-slash, no slash-star-star; asterisk globs avoided).
 */
