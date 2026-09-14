package me.manga.kira.platform.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

private val androidAvifPermit = AndroidAvifPermit(Semaphore(1, true))

/** Own the whole read/native operation; helpers inside [block] must not acquire again. */
internal suspend fun <T> withAndroidAvifPermit(block: suspend () -> T): T = androidAvifPermit.withPermit(block)

/** Synchronous worker-context counterpart for probes; never call from inside the suspend permit. */
internal fun <T> withAndroidAvifNativePermit(block: () -> T): T = androidAvifPermit.withNativePermit(block)

/** A real semaphore is injectable so cancellation tests can observe ownership without codec doubles. */
internal class AndroidAvifPermit(
    private val semaphore: Semaphore,
) {
    suspend fun <T> withPermit(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val acquired = AtomicBoolean(false)
        try {
            runInterruptible(Dispatchers.IO) {
                semaphore.acquire()
                acquired.set(true)
            }
            context.ensureActive()
            return block()
        } finally {
            // runInterruptible/withContext waits for its synchronous block to finish. Its return
            // dispatch can still be cancelled, so ownership cannot live in its discarded result.
            if (acquired.getAndSet(false)) semaphore.release()
        }
    }

    fun <T> withNativePermit(block: () -> T): T {
        var acquired = false
        try {
            semaphore.acquire()
            acquired = true
            ensureThreadActive()
            val result = block()
            ensureThreadActive()
            return result
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("AVIF native operation was interrupted.").initCause(interrupted)
        } finally {
            if (acquired) semaphore.release()
        }
    }
}

private fun ensureThreadActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("AVIF native operation was interrupted.")
}
