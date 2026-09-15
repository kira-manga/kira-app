package me.manga.kira.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/** Main-thread ownership of the renderer currently attached to one remembered Reader destination. */
internal class ReaderNativeLifecycle(
    private val scope: CoroutineScope,
    private val onResumed: () -> Unit,
    private val onPaused: () -> Unit,
) {
    private var attachment: ReaderNativeAttachment? = null

    fun attach(): ReaderNativeAttachment {
        // Revoke the old renderer before installing its replacement. Late appearance/scene/deinit
        // callbacks on the old attachment cannot end the replacement's span or cancel its collectors.
        attachment?.detach()
        return ReaderNativeAttachment(
            scope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job])),
            onResumed = onResumed,
            onPaused = onPaused,
        ).also { attachment = it }
    }

    fun close() {
        val previous = attachment
        attachment = null
        previous?.detach()
        // The destination may mount a new VC after a WebView round trip; close is not a terminal latch.
    }
}

/**
 * One native VC's lifecycle lease. Swift supplies confirmed visibility and its own scene's activity.
 * All calls are main-thread confined. Detaching is terminal for this lease, not for the destination.
 */
class ReaderNativeAttachment internal constructor(
    internal val scope: CoroutineScope,
    private val onResumed: () -> Unit,
    private val onPaused: () -> Unit,
) {
    private var visible = false
    private var sceneActive = false
    private var reading = false
    private var detached = false

    fun onVisibilityChanged(visible: Boolean) {
        if (detached) return
        this.visible = visible
        reconcile()
    }

    fun onSceneActiveChanged(active: Boolean) {
        if (detached) return
        sceneActive = active
        reconcile()
    }

    fun detach() {
        if (detached) return
        detached = true
        // End before cancelling this renderer's collectors. The VM owns statistics persistence,
        // independently of those collectors and of the Compose destination's observation scope.
        try {
            reconcile()
        } finally {
            scope.cancel()
        }
    }

    private fun reconcile() {
        val shouldRead = !detached && visible && sceneActive
        if (reading == shouldRead) return
        reading = shouldRead
        if (shouldRead) onResumed() else onPaused()
    }
}
