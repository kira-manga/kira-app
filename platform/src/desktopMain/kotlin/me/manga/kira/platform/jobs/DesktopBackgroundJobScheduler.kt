package me.manga.kira.platform.jobs

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop actual for [BackgroundJobScheduler] backed by [java.util.concurrent.ScheduledExecutorService].
 *
 * Because there is no `Worker` class on Desktop, callers must register a [BackgroundJobRunner]
 * per [BackgroundJob.tag] via [registerRunner] before scheduling jobs with that tag. The runner
 * receives the job's primitive `data` map and is expected to perform the work synchronously
 * (the scheduler does not block suspending callers — it dispatches into the executor pool).
 *
 * `requiresNetwork` / `requiresCharging` are ignored on Desktop (no platform-level constraint API).
 */
class DesktopBackgroundJobScheduler : BackgroundJobScheduler {

    private val log = Logger.withTag(TAG)

    private val executor = Executors.newScheduledThreadPool(THREAD_POOL_SIZE) { runnable ->
        Thread(runnable, "$THREAD_NAME_PREFIX${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private val futures = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val runners = ConcurrentHashMap<String, BackgroundJobRunner>()

    /**
     * Register the implementation that should run for jobs tagged [tag]. Last-write-wins.
     */
    fun registerRunner(tag: String, runner: BackgroundJobRunner) {
        runners[tag] = runner
    }

    override fun scheduleOneOff(job: BackgroundJob): String {
        val id = UUID.randomUUID().toString()
        val future = executor.schedule(
            // Evict the entry once the one-off job finishes so the map doesn't grow for the
            // process lifetime (periodic futures legitimately stay until cancelled).
            { try { runJob(job) } finally { futures.remove(id) } },
            job.initialDelayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        futures[id] = future
        return id
    }

    override fun schedulePeriodic(job: BackgroundJob, intervalMinutes: Long): String {
        val id = UUID.randomUUID().toString()
        val periodMs = TimeUnit.MINUTES.toMillis(intervalMinutes.coerceAtLeast(1L))
        val future = executor.scheduleAtFixedRate(
            { runJob(job) },
            job.initialDelayMs.coerceAtLeast(0L),
            periodMs,
            TimeUnit.MILLISECONDS,
        )
        futures[id] = future
        return id
    }

    override fun cancel(jobId: String) {
        futures.remove(jobId)?.cancel(false)
    }

    override fun cancelAll() {
        futures.values.forEach { it.cancel(false) }
        futures.clear()
    }

    /**
     * Desktop placeholder — emits `JobState.Idle` once. Coarse state could be derived from
     * `ScheduledFuture.isDone` / `isCancelled`, but that loses the SUCCEEDED-vs-FAILED
     * distinction (thrown runner exceptions are swallowed in [runJob]). A later phase can
     * track per-job state in a `MutableStateFlow` keyed by jobId.
     */
    override fun observeJobState(jobId: String): Flow<JobState> = flowOf(JobState.Idle)

    private fun runJob(job: BackgroundJob) {
        val runner = runners[job.tag]
        if (runner == null) {
            log.w { "No runner registered for tag=${job.tag}; skipping execution" }
            return
        }
        try {
            runner.run(job.data)
        } catch (t: Throwable) {
            // Catch Throwable (not just Exception): scheduleAtFixedRate suppresses all subsequent
            // executions if a task throws, so an Error from a runner would silently kill a periodic
            // job. Logging it keeps periodic scheduling alive and the failure visible.
            log.e(t) { "Runner for tag=${job.tag} threw" }
        }
    }

    private companion object {
        const val TAG = "BackgroundJobScheduler"
        const val THREAD_POOL_SIZE = 2
        const val THREAD_NAME_PREFIX = "yami-jobs-"
        val threadCounter = AtomicInteger(0)
    }
}

/** SAM contract for code that knows how to do a specific [BackgroundJob.tag]'s work on Desktop. */
fun interface BackgroundJobRunner {
    fun run(data: Map<String, String>)
}

/*
 * §253 audit-trail postscript — cluster273 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE-INTERFACE-BOUND-VIA-LEGACY-EXPECT (rework concrete NOT-YET-WIRED).
 *
 * UNIT KIND: platform-facade — Desktop actual of the Phase 5.y.4 BackgroundJobScheduler
 * relocation, plus the Desktop-only SAM extension BackgroundJobRunner. Implements the
 * rework interface declared at
 * platform/src/commonMain/.../platform/jobs/BackgroundJobScheduler.kt:22 (expect-decl
 * already swept in clusters 144-149).
 *
 * LIVE evidence:
 *  - The rework interface BackgroundJobScheduler IS LIVE: DesktopBackgroundJobScheduler:23
 *    declares it as its supertype.
 *  - The CONCRETE class DesktopBackgroundJobScheduler is NOT bound in any rework Koin module:
 *    repo-wide grep for the three impl names yields ZERO "single { ... }" call sites.
 *  - The ONLY LIVE scheduler Koin binding today is the LEGACY :shared expect class at
 *    shared/src/desktopMain/.../di/PlatformModule.desktop.kt:94
 *    (single { BackgroundJobScheduler() }), consumed transitively by
 *    data/src/commonMain/.../repository/LibraryRefreshRepositoryImpl.kt:89 (imports the
 *    LEGACY me.manga.kira.core.jobs.BackgroundJobScheduler at line 10).
 *  - registerRunner has NO caller in the repo yet; the Desktop runner-table stays empty until
 *    the Phase 11 worker-port turn wires both the binding and a tag registration.
 * Status: FULFILLED-PORT (relocation landed) but inert at runtime.
 *
 * Delta-axes (this Desktop actual's distinct approach):
 *  1. Platform API: java.util.concurrent.ScheduledExecutorService — newScheduledThreadPool(2)
 *     with daemon threads. scheduleOneOff uses schedule(); schedulePeriodic uses
 *     scheduleAtFixedRate(). No Worker classpath, so work is dispatched through a tag-keyed
 *     BackgroundJobRunner registered via registerRunner (last-write-wins ConcurrentHashMap).
 *  2. Threading/dispatcher: a fixed daemon thread pool of size 2; jobs run on pool threads,
 *     non-blocking to callers. ConcurrentHashMap guards both futures and runners maps.
 *  3. Error handling: runner exceptions are caught in runJob and logged via log.e (swallowed
 *     so the executor task does not die); a missing runner for a tag is a w-log skip. This is
 *     why observeJobState cannot distinguish SUCCEEDED from FAILED (documented at lines 76-81).
 *  4. DI binding mechanism: no-arg constructor; intended future binding is
 *     single { DesktopBackgroundJobScheduler() } — currently absent.
 *  5. 3-actual contract parity: all five interface methods overridden. UNLIKE the Android
 *     sibling, observeJobState is a coarse placeholder emitting flowOf(JobState.Idle) once;
 *     it shares that no-lifecycle-tracking posture with the iOS sibling. The Desktop-OUTLIER
 *     piece is registerRunner — a public non-interface fun absent from both other actuals,
 *     load-bearing because Desktop has no Worker-class analogue.
 * Nested-comment hazard check: this file has 4 legitimate KDoc/comment openers (class-doc
 * line 13, registerRunner doc line 36, observeJobState doc line 76, BackgroundJobRunner
 * doc line 105). The appended block is balanced — exactly one opener (slash-star), one
 * closer (star-slash), and zero interior comment delimiters anywhere in the prose.
 */
