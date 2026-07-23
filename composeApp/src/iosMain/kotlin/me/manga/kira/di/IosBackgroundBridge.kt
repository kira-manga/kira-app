package me.manga.kira.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.platform.download.IosBackgroundScheduler
import me.manga.kira.presentation.features.download.DownloadEngineFlags
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import org.koin.mp.KoinPlatform

/**
 * Swift-callable bridge for the iOS background-download engine (background-downloads M2–M6). Lives in
 * `:composeApp/iosMain` — the same Swift-facing seam as `IosKoin.kt` — so it can resolve the Koin
 * graph the host bootstrapped via `bootstrapIosKoin()`.
 *
 * Every entry point no-ops cleanly when [DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED] is
 * `false` (the legacy coroutine engine uses no background `URLSession` or BG tasks), while still
 * honouring iOS's contracts. All activity is traced under the `KiraBgDownload` tag ([BgDownloadLog]).
 */

private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private var backgroundWorkJob: Job? = null

private const val MAX_BG_CYCLES = 120
private const val BG_CYCLE_DELAY_MS = 5_000L

// Continued-task (Live Activity) loop: report progress on a FAST cadence so the Dynamic Island tracks
// the chapter smoothly (matching the per-page notification), but run the heavier reconcile/finalize only
// every Nth poll. Previously progress was sampled on the 5s reconcile tick, so a chapter that downloaded
// in <5s jumped 0%→100% with nothing in between.
private const val CONTINUED_POLL_MS = 750L
private const val CONTINUED_RECONCILE_EVERY = 7      // ~5.25s between reconciles
private const val MAX_CONTINUED_POLLS = 800          // runaway backstop (~10 min); iOS expires long before

// ---- M2: background URLSession relaunch wiring ----

/**
 * Host → logging: set the verbose `KiraBgDownload` info stream from the Swift host's
 * build/distribution detection at launch. Debug + TestFlight builds keep the full trace for QA; an
 * **App Store** build turns it off — the "flip VERBOSE before release" checklist item is enforced
 * at runtime instead of remembered. `warn`/`error` lines are never gated. Independent of the
 * engine flag (logging spans both engines).
 */
fun setBgDownloadVerboseLogging(enabled: Boolean) {
    BgDownloadLog.VERBOSE = enabled
    BgDownloadLog.log("bridge.verboseLogging", "enabled" to enabled) // emits only when enabling
}

fun handleBackgroundUrlSessionEvents(identifier: String, completionHandler: () -> Unit) {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
        BgDownloadLog.log("bridge.handleEvents.flagOff", "identifier" to identifier)
        completionHandler()
        return
    }
    BgDownloadLog.log("bridge.handleEvents", "identifier" to identifier)
    val koin = KoinPlatform.getKoin()
    koin.get<DownloadRepository>() // ensure engine + transport listener are wired
    koin.get<BackgroundTransport>().setSystemCompletionHandler(completionHandler)
}

// ---- M4: BG-task CPU scheduling (BGProcessingTask / BGContinuedProcessingTask) ----

fun setDownloadProcessingScheduler(hook: () -> Unit) {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) return
    BgDownloadLog.log("bridge.setScheduler")
    (KoinPlatform.getKoin().get<BackgroundScheduler>() as? IosBackgroundScheduler)?.setHook(hook)
}

fun hasPendingDownloadWork(): Boolean {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) return false
    val pending = KoinPlatform.getKoin().get<BackgroundWorkSignal>().hasPendingWork
    BgDownloadLog.log("bridge.hasPendingWork", "pending" to pending)
    return pending
}

/**
 * True when real **transfer** work remains (a QUEUED chapter or a RUNNING one < 100%), vs only
 * finalize-pending chapters. The host gates *re-arming* the next chapter's Live Activity on this so a
 * finalize-only tail can't busy-loop continued-task submissions (there'd be nothing transferring to show).
 */
fun hasTransferWork(): Boolean {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) return false
    val v = KoinPlatform.getKoin().get<BackgroundWorkSignal>().hasTransferWork
    BgDownloadLog.log("bridge.hasTransferWork", "value" to v)
    return v
}

/**
 * Host → engine: report device stress split into its two independent causes — thermal pressure
 * (`ProcessInfo.thermalState` serious/critical) and Low Power Mode (`ProcessInfo.isLowPowerModeEnabled`).
 * The engine defers FOREGROUND CBZ compression while thermally stressed (ALWAYS) or in Low Power Mode
 * (unless the user opted in via the settings toggle); background compression is unaffected. Called by the
 * Swift host on the thermal/power-state change notifications + foreground — those APIs aren't in the
 * Kotlin/Native Foundation binding, so the host owns the read.
 *
 * Pure state push: the engine observes these two flags (and the user toggle) and OWNS re-driving any
 * deferred finalize when a gate clears (see `BackgroundUrlSessionDownloadRepository`), so — unlike the
 * previous combined setter — there is deliberately no edge-pump here.
 */
fun setDownloadDeviceStressState(thermalStressed: Boolean, lowPowerMode: Boolean) {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) return
    val signal = KoinPlatform.getKoin().get<BackgroundWorkSignal>()
    signal.setThermallyStressed(thermalStressed)
    signal.setLowPowerMode(lowPowerMode)
    BgDownloadLog.log("bridge.deviceStress", "thermal" to thermalStressed, "lowPower" to lowPowerMode)
}

fun currentDownloadProgress(): Float {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) return 1f
    return KoinPlatform.getKoin().get<BackgroundWorkSignal>().progressPercent / 100f
}

/**
 * Run download orchestration during an OS-granted background window: loop reconcile + a brief wait
 * (transfers continue on the background `URLSession` meanwhile) until no pending work remains, the
 * cycle cap is hit, or [cancelBackgroundDownloadWork] fires (the BG task's expiration handler).
 * Reports 0..1 progress each cycle and calls [completion] when done — the host then calls
 * `setTaskCompleted`.
 */
fun runBackgroundDownloadWork(onProgress: (Float) -> Unit, completion: () -> Unit) {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
        BgDownloadLog.log("bgwork.skipped", "reason" to "flagOff")
        completion()
        return
    }
    val koin = KoinPlatform.getKoin()
    val engine = koin.get<DownloadRepository>()
    val signal = koin.get<BackgroundWorkSignal>()
    val previous = backgroundWorkJob
    BgDownloadLog.log("bgwork.start")
    backgroundWorkJob = bridgeScope.launch {
        // Serialize against a superseded window's loop: cancel() alone let the OLD job's finally
        // (setBackgroundProcessingActive(false) + its completion) run asynchronously AFTER this job
        // set the flag true — the whole new window then ran with background finalize gated off.
        // cancelAndJoin guarantees the old finally (incl. its task's setTaskCompleted) fully ran first.
        previous?.cancelAndJoin()
        // Mark the window active so the engine may run CBZ/finalize in the background now (not just in
        // the foreground) — this window has genuine OS-granted CPU. Cleared in finally.
        signal.setBackgroundProcessingActive(true)
        var cycles = 0
        try {
            while (isActive && cycles < MAX_BG_CYCLES) {
                runCatching { engine.reconcileInterruptedDownloads() }
                val pending = signal.hasPendingWork
                BgDownloadLog.log("bgwork.cycle", "cycle" to cycles, "pending" to pending, "progress" to signal.progressPercent)
                onProgress(signal.progressPercent / 100f)
                if (!pending) break
                cycles++
                delay(BG_CYCLE_DELAY_MS)
            }
        } finally {
            signal.setBackgroundProcessingActive(false)
            onProgress(1f)
            BgDownloadLog.log("bgwork.done", "cycles" to cycles)
            completion()
        }
    }
}

/**
 * Drive a **single chapter** (the lead/front-runner) for the iOS 26 `BGContinuedProcessingTask`'s
 * Live Activity (the one-chapter-at-a-time model). Locks onto the lead chapter once one exists, reports
 * ITS transfer progress, and completes at the safe checkpoint — when that chapter LEAVES the active set
 * (reached `finalize.success`/terminal). While the chapter is DOWNLOADED/COMPRESSING it stays at 100% in
 * the snapshot, so the window is held through the CBZ encode and the Dynamic Island dismisses at 100%
 * only once the durable artifact is ready (or the OS expires the window — the host then dismisses it
 * cleanly at the current boundary rather than letting it show "failed"). The rest of the queue is NOT
 * this task's concern: it continues across later windows (BGProcessingTask continuity) / a fresh
 * foreground Live Activity. Transfers themselves always run on the background `URLSession`, independent
 * of this loop.
 */
fun runContinuedChapterBatch(onProgress: (Float) -> Unit, completion: () -> Unit) {
    if (!DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
        BgDownloadLog.log("bgwork.skipped", "reason" to "flagOff")
        completion()
        return
    }
    val koin = KoinPlatform.getKoin()
    val engine = koin.get<DownloadRepository>()
    val signal = koin.get<BackgroundWorkSignal>()
    val previous = backgroundWorkJob
    BgDownloadLog.log("bgwork.start", "mode" to "continuedChapterBatch")
    backgroundWorkJob = bridgeScope.launch {
        // Same serialization as runBackgroundDownloadWork: the superseded job's finally must not clear
        // backgroundProcessingActive after this window set it.
        previous?.cancelAndJoin()
        signal.setBackgroundProcessingActive(true)
        var trackedId: Long? = null
        var polls = 0
        try {
            while (isActive && polls < MAX_CONTINUED_POLLS) {
                // Reconcile/finalize is heavy → run it only every CONTINUED_RECONCILE_EVERY polls.
                if (polls % CONTINUED_RECONCILE_EVERY == 0) {
                    runCatching { engine.reconcileInterruptedDownloads() }
                }
                // Lock onto the chapter this Live Activity represents (the front-runner) once one exists.
                if (trackedId == null) trackedId = signal.leadChapterId
                val tracked = trackedId
                val progress = signal.chapterProgress
                val trackedPct = tracked?.let { progress[it] } ?: 0
                // Push progress EVERY poll (cheap) so the Dynamic Island tracks the chapter smoothly,
                // matching the per-page notification instead of jumping in 5s steps.
                onProgress(trackedPct / 100f)
                // Safe checkpoint: tracked chapter left the active set → it finalized (or went terminal).
                val trackedDone = tracked != null && tracked !in progress
                if (polls % CONTINUED_RECONCILE_EVERY == 0 || trackedDone) {
                    BgDownloadLog.log(
                        "bgwork.cycle", "mode" to "chapterBatch",
                        "tracked" to tracked, "trackedPct" to trackedPct, "trackedDone" to trackedDone,
                        "transferWork" to signal.hasTransferWork,
                    )
                }
                // Complete when TRANSFER work is done, NOT when no work is "pending". A Live Activity is a
                // transfer-progress UI; once transfers finish, a chapter sitting in DOWNLOADED awaiting
                // (background) CBZ compression is invisible post-processing. Gating on hasPendingWork made
                // this loop spin ~88s (until iOS expired it) whenever compression was deferred — the chapter
                // never left the active set, so neither trackedDone nor !hasPendingWork ever fired.
                if (trackedDone || !signal.hasTransferWork) break
                polls++
                delay(CONTINUED_POLL_MS)
            }
        } finally {
            signal.setBackgroundProcessingActive(false)
            onProgress(1f)
            BgDownloadLog.log("bgwork.done", "mode" to "chapterBatch", "tracked" to trackedId, "polls" to polls)
            completion()
        }
    }
}

fun cancelBackgroundDownloadWork() {
    BgDownloadLog.log("bgwork.cancel")
    backgroundWorkJob?.cancel()
    backgroundWorkJob = null
}
