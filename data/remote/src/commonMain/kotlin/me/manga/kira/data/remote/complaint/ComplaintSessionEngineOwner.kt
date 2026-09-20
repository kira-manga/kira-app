package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Owns only a dedicated installation or history engine. Its dedicated client borrows [engine]; the
 * composition root closes that client before closing this owner. Closing stops admission/cancels
 * work, not a claim that every operating-system callback or socket has synchronously drained.
 */
interface ComplaintSessionEngineOwner {
    /** Borrow only for the factory's closed route set; never reuse as an arbitrary-host client. */
    val engine: HttpClientEngine

    /** Idempotently closes admission and cancels/releases this owner's native resources. */
    fun close()
}

@OptIn(ExperimentalAtomicApi::class)
internal class OwnedComplaintSessionEngine(
    override val engine: HttpClientEngine,
    private val stopNativeWork: () -> Unit,
    private val releaseNativeResources: () -> Unit,
) : ComplaintSessionEngineOwner {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        try {
            stopNativeWork()
        } finally {
            closeEngine()
        }
    }

    private fun closeEngine() {
        try {
            engine.coroutineContext.cancel()
        } finally {
            try {
                engine.close()
            } finally {
                releaseNativeResources()
            }
        }
    }
}
