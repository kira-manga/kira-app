package me.manga.kira.platform.download

/**
 * Requests OS background CPU time to continue download **orchestration** — preparation (scraping the
 * next chapter's page list), reconciliation, retry, and finalization (CBZ) — i.e. the work the
 * background `URLSession` cannot do itself (it only transfers already-resolved files). Best-effort:
 * the OS decides if and when to grant the window.
 *
 * The iOS implementation ([IosBackgroundScheduler]) bridges to the host's `BGTaskScheduler`
 * (`BGProcessingTask` before iOS 26, `BGContinuedProcessingTask` on 26+). Desktop/Android don't use
 * it — their engines run in-process / via WorkManager — so they bind [NoOp].
 */
interface BackgroundScheduler {
    fun scheduleProcessing()

    object NoOp : BackgroundScheduler {
        override fun scheduleProcessing() {}
    }
}
