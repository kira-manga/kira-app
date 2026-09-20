package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLock

/** Holds one drain receipt until both native callback cleanup and every accepted receiver settle. */
@OptIn(ExperimentalForeignApi::class)
internal class IosTransferEvent(
    operation: DownloadOperationExclusion.Operation,
    private val complete: () -> Unit,
) {
    private val ownership = operation.retain()
    private val lock = NSLock()
    private var pending = 1
    private val deliveryAcknowledged = acknowledgement()

    /** A throwing receiver rejects ownership; the caller still owns its callback-local cleanup. */
    fun deliver(receiver: (operation: DownloadOperationExclusion.Operation, acknowledge: () -> Unit) -> Unit) {
        locked {
            check(pending > 0)
            pending++
        }
        val acknowledge = acknowledgement()
        try {
            receiver(ownership, acknowledge)
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
            if (finished) {
                try {
                    complete()
                } finally {
                    ownership.release()
                }
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
}
