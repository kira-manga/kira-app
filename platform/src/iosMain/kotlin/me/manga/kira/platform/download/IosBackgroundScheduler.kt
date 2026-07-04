package me.manga.kira.platform.download

/**
 * iOS [BackgroundScheduler]. The actual `BGTaskScheduler` calls live in Swift (newest-SDK APIs +
 * host lifecycle), so this just invokes a hook the host registers once at launch.
 *
 * [scheduleProcessing] asks the host to schedule a `BGProcessingTask` (the opportunistic pre-26
 * path); the host independently submits a `BGContinuedProcessingTask` on backgrounding when on iOS
 * 26+ (the stronger path, with a system progress indicator).
 */
class IosBackgroundScheduler : BackgroundScheduler {
    private var hook: (() -> Unit)? = null

    /** Registered by the Swift host at launch (via the IosBackgroundBridge). */
    fun setHook(hook: () -> Unit) {
        BgDownloadLog.log("scheduler.hookSet")
        this.hook = hook
    }

    override fun scheduleProcessing() {
        val h = hook
        BgDownloadLog.log("scheduler.scheduleProcessing", "hookRegistered" to (h != null))
        h?.invoke()
    }
}
