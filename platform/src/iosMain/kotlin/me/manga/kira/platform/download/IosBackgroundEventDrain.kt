package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Receipt barrier for one delivered URLSession event window, not for the session's future tasks.
 * Admissions, acknowledgements and host/delegate calls may race; user code never runs under [lock].
 * A handler replacement before finish is additive: neither Apple-owned completion is discarded.
 * Once finish captures handlers, later handlers/events belong to another window.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosBackgroundEventDrain {
    private val lock = NSLock()
    private var current = Window()

    /**
     * Call synchronously before handing an event to an asynchronous receiver. The receiver must
     * acknowledge ONLY after its immediate durable outcome and owned-file disposal. If handoff
     * fails or no receiver exists, the caller retains that cleanup/acknowledgement responsibility.
     * This does not auto-acknowledge a Unit callback, cancellation, or an arbitrary scope's Job.
     */
    fun admitEvent(): () -> Unit {
        val window =
            locked {
                current.apply {
                    finished = false // New events invalidate an unclaimed older finish boundary.
                    hasEvents = true
                    pendingEvents++
                }
            }
        var acknowledged = false
        return {
            val handlers =
                locked {
                    if (acknowledged) return@locked emptyList()
                    acknowledged = true
                    window.pendingEvents--
                    takeReadyHandlers(window)
                }
            dispatch(handlers)
        }
    }

    fun setCompletionHandler(handler: () -> Unit) {
        val handlers =
            locked {
                val window = current
                window.handlers += handler
                // A late host may claim an unowned boundary, but not one followed by new events.
                if (window.finished) current = Window()
                takeReadyHandlers(window)
            }
        dispatch(handlers)
    }

    fun finishEvents() {
        val handlers =
            locked {
                val window = current
                // Ignore empty, unowned boundaries (including duplicate native finish callbacks).
                if (!window.hasEvents && window.handlers.isEmpty()) return@locked emptyList()
                window.finished = true
                if (window.handlers.isNotEmpty()) current = Window()
                takeReadyHandlers(window)
            }
        dispatch(handlers)
    }

    private fun takeReadyHandlers(window: Window): List<() -> Unit> {
        if (!window.finished || window.pendingEvents != 0) return emptyList()
        return window.handlers.toList().also { window.handlers.clear() }
    }

    private fun dispatch(handlers: List<() -> Unit>) {
        handlers.forEach { handler ->
            dispatch_async(dispatch_get_main_queue()) {
                BgDownloadLog.log("session.completionHandler.invoked")
                handler()
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private class Window {
        var pendingEvents = 0
        var hasEvents = false
        var finished = false
        val handlers = mutableListOf<() -> Unit>()
    }
}
