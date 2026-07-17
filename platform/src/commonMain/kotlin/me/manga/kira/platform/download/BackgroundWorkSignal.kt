package me.manga.kira.platform.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * True when the device is thermally stressed (`ProcessInfo.thermalState` serious/critical). A heavy
     * foreground CBZ encode is ALWAYS deferred while this is true, regardless of any user setting (running
     * the encoder would only worsen the thermal state). Written by the iOS host on the thermal-state change
     * notification + foreground. Observable (StateFlow) so the engine can re-drive deferred work when it
     * clears. Advisory; only gates foreground compression admission, never correctness. Defaults false.
     */
    private val _thermallyStressed = MutableStateFlow(false)
    val thermallyStressed: StateFlow<Boolean> = _thermallyStressed.asStateFlow()

    /**
     * True when iOS Low Power Mode is enabled (`ProcessInfo.isLowPowerModeEnabled`). By default foreground
     * compression is deferred while this is true (respect the user's battery-saving intent); the user may
     * opt in via a settings toggle to compress anyway. Separated from [thermallyStressed] so ONLY the
     * Low-Power half is user-overridable. Written by the iOS host on the power-state change notification +
     * foreground; observable so the engine can re-drive deferred work and the UI can surface a clear
     * "paused (Low Power Mode)" state instead of an endless "Finalizing…". Defaults false.
     */
    private val _lowPowerMode = MutableStateFlow(false)
    val lowPowerMode: StateFlow<Boolean> = _lowPowerMode.asStateFlow()

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

    fun setThermallyStressed(stressed: Boolean) {
        _thermallyStressed.value = stressed
    }

    fun setLowPowerMode(enabled: Boolean) {
        _lowPowerMode.value = enabled
    }
}
