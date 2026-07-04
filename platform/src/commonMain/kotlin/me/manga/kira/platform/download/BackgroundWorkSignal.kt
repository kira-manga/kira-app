package me.manga.kira.platform.download

/**
 * A lightweight snapshot of background-download progress the host's BG-task layer can read
 * **synchronously** — to decide "is there work, so should I submit a continued task?" and to drive a
 * `BGContinuedProcessingTask`'s progress UI. Written by the download engine as Room state changes and
 * read from the iOS host. Values are advisory (a benign cross-thread read is fine under the K/N
 * memory model); nothing depends on them for correctness.
 */
class BackgroundWorkSignal {
    var hasPendingWork: Boolean = false
        private set
    var progressPercent: Int = 0
        private set

    /**
     * Per-chapter transfer progress (chapterId → 0..100) for every active chapter, plus the [leadChapterId]
     * the host's **one-chapter-at-a-time** Live Activity should track (the front-runner — the RUNNING
     * chapter nearest done). The iOS 26 `BGContinuedProcessingTask` reports the lead chapter's progress and
     * completes when it reaches 100% (DOWNLOADED), so the Dynamic Island reaches 100% and dismisses as done
     * per chapter instead of perpetually representing the whole multi-hundred-page queue.
     */
    var chapterProgress: Map<Long, Int> = emptyMap()
        private set
    var leadChapterId: Long? = null
        private set

    /**
     * True when there is real **transfer** work — a QUEUED chapter (will transfer) or a RUNNING chapter
     * still below 100% (transferring). False when only finalize-pending (DOWNLOADED/COMPRESSING) chapters
     * remain. The host gates *re-arming* the next chapter's Live Activity on this, so a finalize-only tail
     * (every chapter already at 100% transfer) can't busy-loop continued-task submissions.
     */
    var hasTransferWork: Boolean = false
        private set

    /**
     * True only while an OS-granted background CPU window (a `BGContinuedProcessingTask` on iOS 26+,
     * or a `BGProcessingTask` before 26) is actively running the engine. It lets the engine finalize
     * (CBZ encode + bookkeeping) **in the background** during that window — not only in the foreground
     * — since the app genuinely has CPU then. Set by the host bridge around its work loop.
     */
    var backgroundProcessingActive: Boolean = false
        private set

    /**
     * True when the device is under stress and a heavy foreground CBZ encode should be deferred: thermal
     * state serious/critical, or Low Power Mode on. Written by the iOS host from `ProcessInfo.thermalState`
     * / `isLowPowerModeEnabled` (those aren't in the Kotlin/Native Foundation binding, but are trivial in
     * Swift) on the thermal/power-state change notifications + foreground. Advisory; only gates foreground
     * compression admission, never correctness. Defaults false (no gating until the host wires it).
     */
    var deviceUnderStress: Boolean = false
        private set

    fun update(
        hasPendingWork: Boolean,
        progressPercent: Int,
        chapterProgress: Map<Long, Int> = emptyMap(),
        leadChapterId: Long? = null,
        hasTransferWork: Boolean = false,
    ) {
        this.hasPendingWork = hasPendingWork
        this.progressPercent = progressPercent.coerceIn(0, 100)
        this.chapterProgress = chapterProgress
        this.leadChapterId = leadChapterId
        this.hasTransferWork = hasTransferWork
    }

    fun setBackgroundProcessingActive(active: Boolean) {
        backgroundProcessingActive = active
    }

    fun setDeviceUnderStress(stressed: Boolean) {
        deviceUnderStress = stressed
    }
}
