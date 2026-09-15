package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock

/** Holds one drain receipt until both native callback cleanup and every accepted receiver settle. */
@OptIn(ExperimentalForeignApi::class)
internal class IosTransferEvent(
    private val complete: () -> Unit,
) {
    private val lock = NSLock()
    private var pending = 1
    private val deliveryAcknowledged = acknowledgement()

    /** A throwing receiver rejects ownership; the caller still owns its callback-local cleanup. */
    fun deliver(receiver: (acknowledge: () -> Unit) -> Unit) {
        locked {
            check(pending > 0)
            pending++
        }
        val acknowledge = acknowledgement()
        try {
            receiver(acknowledge)
        } catch (failure: Throwable) {
            acknowledge()
            throw failure
        }
    }

    fun finishDelivery() = deliveryAcknowledged()

    private fun acknowledgement(): () -> Unit {
        var acknowledged = false
        return {
            val finished =
                locked {
                    if (acknowledged) return@locked false
                    acknowledged = true
                    --pending == 0
                }
            if (finished) complete()
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
}
