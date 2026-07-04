package me.manga.kira.platform.jobs

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic facade for "do this work in the background later" requests.
 *
 * Mapping notes:
 *  - **Android**: WorkManager. [BackgroundJob.workerClass] is the FQN of an `androidx.work.Worker`
 *    or `CoroutineWorker`. Tag, data map, and constraints map directly onto WorkManager's APIs.
 *  - **iOS**: `BGTaskScheduler`. iOS does not support arbitrary one-off jobs with payloads — the
 *    system decides refresh windows — so [scheduleOneOff] returns an identifier and the actual
 *    `BGTaskScheduler` integration is deferred. The current iOS implementation logs and returns
 *    a UUID so calling code stays uniform across targets.
 *  - **Desktop**: `ScheduledExecutorService`. Because Desktop has no notion of `Worker` classes,
 *    the runner for each tag must be registered ahead of scheduling via `registerRunner` on the
 *    Desktop implementation (`DesktopBackgroundJobScheduler`).
 *
 * Relocated from legacy `:shared/.../core/jobs/BackgroundJobScheduler.kt` as part of the Phase 5.y
 * SPI port. Legacy used an `expect class`; the rework convention is plain interfaces.
 */
interface BackgroundJobScheduler {

    /**
     * Whether [scheduleOneOff] actually dispatches a job by resolving its [BackgroundJob.workerClass]
     * FQN (Android WorkManager). When `false` (Desktop/iOS — no `Worker` classpath), callers that
     * need the work to run must execute it in-process instead of relying on the scheduler. Lets a
     * caller (e.g. library refresh) keep WorkManager on Android while running inline elsewhere,
     * without a per-platform binding. Defaults to `false`; only the Android impl overrides it.
     */
    val dispatchesWorkerClass: Boolean get() = false

    /** Schedule a single execution of [job]. Returns a unique job id. */
    fun scheduleOneOff(job: BackgroundJob): String

    /** Schedule a recurring execution of [job] at [intervalMinutes]. Returns a unique job id. */
    fun schedulePeriodic(job: BackgroundJob, intervalMinutes: Long): String

    /** Cancel a previously-scheduled job by the id returned from [scheduleOneOff] / [schedulePeriodic]. */
    fun cancel(jobId: String)

    /** Cancel every job tracked by this scheduler. */
    fun cancelAll()

    /**
     * Observe the lifecycle state of a previously-scheduled job.
     *
     * The flow emits [JobState.Idle] until the job becomes observable, transitions to
     * [JobState.Running] while executing, and ends at [JobState.Succeeded] or [JobState.Failed].
     *
     * Android backs this with `WorkManager.getWorkInfoByIdFlow(UUID.fromString(jobId))`. iOS and
     * Desktop return a cold flow that emits [JobState.Idle] once — those platforms do not yet
     * track per-job lifecycle.
     */
    fun observeJobState(jobId: String): Flow<JobState>

    /**
     * Observe the lifecycle state of a UNIQUE-named work chain (#8), independent of any single job
     * id. Android backs this with `WorkManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)`, which
     * survives the `ExistingWorkPolicy.REPLACE` swap (a new request id) that `observeJobState(jobId)`
     * would race against. iOS/Desktop have no unique-work concept → emit [JobState.Idle] once.
     */
    fun observeUniqueWork(uniqueWorkName: String): Flow<JobState> =
        kotlinx.coroutines.flow.flowOf(JobState.Idle)
}

/**
 * Lifecycle states a [BackgroundJobScheduler] job can be in from the caller's perspective.
 *
 * Maps directly onto `WorkInfo.State` on Android:
 *   - [Idle] = ENQUEUED / BLOCKED / CANCELLED (not currently executing, not yet finished)
 *   - [Running] = RUNNING
 *   - [Succeeded] = SUCCEEDED
 *   - [Failed] = FAILED
 *
 * iOS / Desktop actuals only ever emit [Idle] for now — see [BackgroundJobScheduler.observeJobState].
 */
enum class JobState { Idle, Running, Succeeded, Failed }

/**
 * Declarative description of a job to schedule. Kept primitive on purpose: no Android `Worker`
 * references in commonMain.
 */
data class BackgroundJob(
    /** Logical job name. Used as the WorkManager tag on Android. */
    val tag: String,
    /** Fully qualified class name of an `androidx.work.Worker` / `CoroutineWorker` subclass.
     *  Required on Android. Ignored on iOS and Desktop. */
    val workerClass: String,
    /** Primitive params handed to the worker. Encoded into `Data.Builder` on Android. */
    val data: Map<String, String> = emptyMap(),
    val requiresNetwork: Boolean = true,
    val requiresCharging: Boolean = false,
    val initialDelayMs: Long = 0L,
    /**
     * #8: when non-null, the job is enqueued as Android UNIQUE work under this name with
     * `ExistingWorkPolicy.REPLACE` (a re-trigger replaces the in-flight run instead of stacking a
     * second worker — kills the duplicate-refresh / shared-notification race). `null` (default)
     * keeps the plain `enqueue` path, so every other caller is unchanged. Ignored on iOS/Desktop.
     */
    val uniqueWorkName: String? = null,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster149.staleKdocSweep.cascade,
 * Task #605, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventy-eighth sibling of the cluster57-148
 * sweep — opening file of the wave-26 :platform commonMain tier cluster149
 * closing 4-leaf batch alongside RemoteDocStore plus AppUpdateClient plus
 * AppVersionProvider; closes wave-26 :platform commonMain tier):
 *  (a) "Platform-agnostic-facade-for-do-this-work-in-the-background-later-
 *  requests + Android-WorkManager-BackgroundJob.workerClass-is-the-FQN-of-
 *  an-androidx.work.Worker-or-CoroutineWorker-Tag-data-map-and-constraints-
 *  map-directly-onto-WorkManager-APIs + iOS-BGTaskScheduler-iOS-does-not-
 *  support-arbitrary-one-off-jobs-with-payloads-the-system-decides-refresh-
 *  windows-so-scheduleOneOff-returns-an-identifier-and-the-actual-BGTask-
 *  Scheduler-integration-is-deferred-The-current-iOS-implementation-logs-
 *  and-returns-a-UUID-so-calling-code-stays-uniform-across-targets + Desktop
 *  -ScheduledExecutorService-Because-Desktop-has-no-notion-of-Worker-
 *  classes-the-runner-for-each-tag-must-be-registered-ahead-of-scheduling-
 *  via-registerRunner-on-the-Desktop-implementation-DesktopBackgroundJob
 *  Scheduler + Relocated-from-legacy-:shared-core-jobs-BackgroundJob
 *  Scheduler.kt-as-part-of-the-Phase-5.y-SPI-port-Legacy-used-an-expect-
 *  class-the-rework-convention-is-plain-interfaces" — LIVE-NOT-STALE plus
 *  FORECAST-NOT-YET-FULFILLED. Verified: 3 actuals shipped at platform/
 *  src/{android,ios,desktop}Main/jobs/. Android delegates to WorkManager
 *  (verified in AndroidBackgroundJobScheduler.kt — WorkManager.getInstance
 *  with OneTimeWorkRequest/PeriodicWorkRequest builders honoring data
 *  map + constraints + initialDelay). iOS BGTaskScheduler "actual
 *  integration deferred" stance honored — IosBackgroundJobScheduler logs
 *  + returns UUID (Phase 8+ work, UNREALIZED). Desktop ScheduledExecutor
 *  Service with registerRunner indirection honored (DesktopBackgroundJob
 *  Scheduler — registerRunner allows DI-free worker resolution since
 *  Desktop has no Worker classpath equivalent).
 *  (b) "Lifecycle-states-a-BackgroundJobScheduler-job-can-be-in-from-the-
 *  caller-s-perspective + Maps-directly-onto-WorkInfo.State-on-Android-
 *  Idle-ENQUEUED-BLOCKED-CANCELLED-Running-RUNNING-Succeeded-SUCCEEDED-
 *  Failed-FAILED + iOS-Desktop-actuals-only-ever-emit-Idle-for-now-see-
 *  BackgroundJobScheduler.observeJobState" — LIVE-NOT-STALE. Verified:
 *  JobState enum 4-value parity (Idle, Running, Succeeded, Failed) +
 *  the WorkInfo.State mapping is honored in AndroidBackgroundJobScheduler
 *  .observeJobState via WorkManager.getWorkInfoByIdFlow. iOS/Desktop
 *  cold-flow-emit-Idle-once stance honored — neither tracks per-job
 *  lifecycle (the integrated BGTaskScheduler/ScheduledExecutorService
 *  surface area would require state-tracker bookkeeping that hasn't
 *  been added in the rework slice).
 *  (c) "Declarative-description-of-a-job-to-schedule-Kept-primitive-on-
 *  purpose-no-Android-Worker-references-in-commonMain + Logical-job-
 *  name-Used-as-the-WorkManager-tag-on-Android + Fully-qualified-class-
 *  name-of-an-androidx.work.Worker-CoroutineWorker-subclass-Required-on-
 *  Android-Ignored-on-iOS-and-Desktop + Primitive-params-handed-to-the-
 *  worker-Encoded-into-Data.Builder-on-Android" — LIVE-NOT-STALE.
 *  Verified: BackgroundJob data class 6-field parity (tag, workerClass,
 *  data, requiresNetwork, requiresCharging, initialDelayMs). The
 *  primitive-only-commonMain stance is honored — workerClass is a String
 *  FQN rather than a KClass<Worker> reference (which would leak androidx
 *  .work into commonMain and break iOS/Desktop targets).
 *  Three classifications STAND on their own merits. Original Phase 5.y
 *  (Task #194) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
