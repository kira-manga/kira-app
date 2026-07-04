package me.manga.kira.platform.jobs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android actual for [BackgroundJobScheduler] backed by WorkManager.
 *
 * `BackgroundJob.workerClass` is resolved via `Class.forName(...)` rather than holding a
 * `KClass` reference in commonMain — this keeps the SPI type-primitive and avoids leaking
 * `androidx.work.Worker` into commonMain.
 *
 * Backoff policy is fixed at `EXPONENTIAL / 30s` for one-off jobs, matching the legacy
 * `:shared` actual. Periodic intervals are clamped to WorkManager's
 * `MIN_PERIODIC_INTERVAL_MILLIS` (15 minutes today; the constant is the source of truth).
 */
class AndroidBackgroundJobScheduler(
    context: Context,
) : BackgroundJobScheduler {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val log = Logger.withTag(TAG)

    // Android resolves BackgroundJob.workerClass via WorkManager + reflection, so scheduled jobs
    // actually run here (unlike Desktop/iOS). Library refresh keeps using WorkManager on Android.
    override val dispatchesWorkerClass: Boolean = true

    override fun scheduleOneOff(job: BackgroundJob): String {
        val workerClass = resolveWorkerClass(job.workerClass)
            ?: return UNKNOWN_JOB_ID.also {
                log.e { "scheduleOneOff: workerClass ${job.workerClass} could not be resolved" }
            }

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(job.toConstraints())
            .setInputData(job.toInputData())
            .addTag(job.tag)
            .apply {
                if (job.initialDelayMs > 0) {
                    setInitialDelay(job.initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        // #8: enqueue as UNIQUE work (REPLACE) when a name is given, so a re-trigger replaces the
        // in-flight run instead of stacking a second worker (kills the duplicate-refresh race);
        // otherwise the plain enqueue path is unchanged for every other caller.
        val uniqueName = job.uniqueWorkName
        if (uniqueName != null) {
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        } else {
            workManager.enqueue(request)
        }
        return request.id.toString()
    }

    override fun schedulePeriodic(job: BackgroundJob, intervalMinutes: Long): String {
        val workerClass = resolveWorkerClass(job.workerClass)
            ?: return UNKNOWN_JOB_ID.also {
                log.e { "schedulePeriodic: workerClass ${job.workerClass} could not be resolved" }
            }
        val safeInterval = intervalMinutes.coerceAtLeast(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / MS_PER_MINUTE,
        )
        val request = PeriodicWorkRequest.Builder(workerClass, safeInterval, TimeUnit.MINUTES)
            .setConstraints(job.toConstraints())
            .setInputData(job.toInputData())
            .addTag(job.tag)
            .apply {
                if (job.initialDelayMs > 0) {
                    setInitialDelay(job.initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .build()

        // #8: mirror scheduleOneOff — enqueue as UNIQUE periodic work (UPDATE) when a name is
        // given, so re-scheduling on each app start replaces the existing worker instead of
        // stacking parallel periodic workers; otherwise the plain enqueue path is unchanged.
        val uniqueName = job.uniqueWorkName
        if (uniqueName != null) {
            workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.enqueue(request)
        }
        return request.id.toString()
    }

    override fun cancel(jobId: String) {
        if (jobId == UNKNOWN_JOB_ID) return
        runCatching {
            workManager.cancelWorkById(UUID.fromString(jobId))
        }.onFailure { log.e(it) { "cancel($jobId) failed" } }
    }

    override fun cancelAll() {
        workManager.cancelAllWork()
    }

    override fun observeJobState(jobId: String): Flow<JobState> {
        if (jobId == UNKNOWN_JOB_ID) return flowOf(JobState.Idle)
        val uuid = runCatching { UUID.fromString(jobId) }.getOrNull()
            ?: return flowOf(JobState.Idle).also {
                log.w { "observeJobState($jobId): not a UUID — returning Idle" }
            }
        return workManager.getWorkInfoByIdFlow(uuid).map { it.toJobState() }
    }

    // #8: observe a unique-named chain — survives the REPLACE swap (new request id) that
    // observeJobState(jobId) would race against. Picks the single WorkInfo for the unique name.
    override fun observeUniqueWork(uniqueWorkName: String): Flow<JobState> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).map { infos ->
            infos.firstOrNull().toJobState()
        }

    private fun WorkInfo?.toJobState(): JobState = when (this?.state) {
        WorkInfo.State.RUNNING -> JobState.Running
        WorkInfo.State.SUCCEEDED -> JobState.Succeeded
        WorkInfo.State.FAILED -> JobState.Failed
        // ENQUEUED / BLOCKED / CANCELLED / null collapse to Idle — callers treat them the same
        // (no spinner, no error). Matches the legacy RefreshViewModel `state == ENQUEUED` boolean.
        else -> JobState.Idle
    }

    private fun BackgroundJob.toConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
            )
            .setRequiresCharging(requiresCharging)
            .build()

    private fun BackgroundJob.toInputData(): Data {
        val builder = Data.Builder()
        data.forEach { (key, value) -> builder.putString(key, value) }
        return builder.build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveWorkerClass(fqn: String): Class<out ListenableWorker>? = runCatching {
        Class.forName(fqn) as Class<out ListenableWorker>
    }.getOrNull()

    private companion object {
        const val TAG = "BackgroundJobScheduler"
        const val UNKNOWN_JOB_ID = "unknown"
        const val BACKOFF_SECONDS = 30L
        const val MS_PER_MINUTE = 60_000L
    }
}

/*
 * §253 audit-trail postscript — cluster273 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-BOUND-VIA-LEGACY-EXPECT (rework concrete NOT-YET-WIRED).
 *
 * UNIT KIND: platform-facade — Android actual of the Phase 5.y.4 BackgroundJobScheduler
 * relocation. This class implements the plain-interface rework SPI declared at
 * platform/src/commonMain/.../platform/jobs/BackgroundJobScheduler.kt:22 (the
 * expect-decl already swept in clusters 144-149; its own postscript classifies the
 * facade "LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED").
 *
 * LIVE evidence:
 *  - The rework interface me.manga.kira.platform.jobs.BackgroundJobScheduler IS LIVE:
 *    its commonMain interface is the type that AndroidBackgroundJobScheduler:33 satisfies.
 *  - The CONCRETE class AndroidBackgroundJobScheduler is NOT yet bound in any rework Koin
 *    module: a repo-wide scan for "AndroidBackgroundJobScheduler" / "DesktopBackgroundJob
 *    Scheduler" / "IosBackgroundJobScheduler" returns ZERO Koin "single { ... }" sites
 *    (only the commonMain interface KDoc and these three impl files reference the names).
 *  - The ONLY LIVE Koin binding for a scheduler today is the LEGACY :shared expect class
 *    me.manga.kira.core.jobs.BackgroundJobScheduler at
 *    shared/src/androidMain/.../di/PlatformModule.android.kt:108
 *    (single { BackgroundJobScheduler(androidContext()) }), consumed by
 *    data/src/commonMain/.../repository/LibraryRefreshRepositoryImpl.kt:89
 *    (constructor dep, imports the LEGACY core.jobs.BackgroundJobScheduler at line 10).
 *  - The binding swap to this rework actual is deferred to the Phase 11 worker-port turn —
 *    see LibraryRefreshRepositoryImpl.kt:21-27 and 36-42 (the strangler-fig DIP note).
 * Status: FULFILLED-PORT (relocation landed) but not the active runtime path yet.
 *
 * Delta-axes (this Android actual's distinct approach):
 *  1. Platform API: androidx.work WorkManager — OneTimeWorkRequest / PeriodicWorkRequest
 *     builders with Constraints, Data, and addTag. ListenableWorker class resolved by FQN
 *     via Class.forName (resolveWorkerClass) to keep commonMain androidx.work-free.
 *  2. Threading/dispatcher: non-suspend; workManager.enqueue is async-internal and returns
 *     immediately. observeJobState bridges WorkManager.getWorkInfoByIdFlow into a Flow.
 *  3. Error handling: unresolvable workerClass returns sentinel UNKNOWN_JOB_ID and logs e;
 *     cancel wraps UUID.fromString in runCatching with onFailure logging; bad jobId in
 *     observeJobState falls back to flowOf(JobState.Idle) with a w-log.
 *  4. DI binding mechanism: constructor takes android.content.Context; intended future
 *     binding is single { AndroidBackgroundJobScheduler(androidContext()) } — currently absent.
 *  5. 3-actual contract parity: scheduleOneOff/schedulePeriodic/cancel/cancelAll/observeJobState
 *     all overridden; this is the ONLY actual with real lifecycle observation (RUNNING /
 *     SUCCEEDED / FAILED mapped from WorkInfo.State; everything else collapses to Idle).
 *     Backoff fixed at EXPONENTIAL / 30s; periodic interval clamped to
 *     MIN_PERIODIC_INTERVAL_MILLIS. iOS + Desktop siblings emit Idle once only.
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the class-doc at
 * line 20). The appended block is balanced — exactly one opener (slash-star), one closer
 * (star-slash), and zero interior comment delimiters (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose; asterisk globs spelled out as words).
 */
