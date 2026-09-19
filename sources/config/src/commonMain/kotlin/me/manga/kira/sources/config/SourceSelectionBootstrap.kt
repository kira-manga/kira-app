package me.manga.kira.sources.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map

/**
 * Coordinates local preparation through the existing manager; the supplied graph owns its lifetime.
 * A successful outcome is only a hint. This owner neither publishes readiness nor grants a selection
 * lease, and it never caches success, retries a failed attempt, or requests a remote refresh.
 */
class SourceSelectionBootstrap(
    private val manager: IncrementalSourceCatalogManager,
    graphScope: CoroutineScope,
) {
    private val ownerJob = SupervisorJob(requireNotNull(graphScope.coroutineContext[Job]))
    private val scope = CoroutineScope(graphScope.coroutineContext + ownerJob)
    private val flightLock = Mutex()
    private var current: Flight? = null

    suspend fun prepareLocal(): AppResult<Unit> {
        val flight = acquireFlight()
        return try {
            currentCoroutineContext().ensureActive()
            ownerJob.ensureActive()
            flight.result.await().also {
                currentCoroutineContext().ensureActive()
                ownerJob.ensureActive()
            }
        } finally {
            detach(flight)
        }
    }

    /** Requests cancellation, not a synchronous join; never cancels the supplied graph's own Job. */
    fun close() {
        ownerJob.cancel()
    }

    private suspend fun acquireFlight(): Flight {
        while (true) {
            val closing = flightLock.withLock {
                currentCoroutineContext().ensureActive()
                ownerJob.ensureActive()
                val flight = current
                if (flight == null || flight.result.isCompleted) {
                    return newFlight().also { current = it }
                }
                if (!flight.closing) {
                    flight.waiters++
                    return flight
                }
                flight.result
            }
            // Only wait for a previous caller's cancellation tail; this is not a preparation retry.
            closing.cancelAndJoin()
        }
    }

    private fun newFlight() = Flight(
        scope.async(start = CoroutineStart.LAZY) { manager.restoreLocalSelection().map { Unit } },
    )

    private suspend fun detach(flight: Flight) {
        withContext(NonCancellable) {
            val cancel = flightLock.withLock {
                check(flight.waiters > 0)
                flight.waiters--
                if (flight.waiters != 0) {
                    false
                } else if (flight.result.isCompleted) {
                    if (current === flight) current = null
                    false
                } else {
                    flight.closing = true
                    true
                }
            }
            // Cancellation handlers may run immediately; never invoke them under the bookkeeping lock.
            if (cancel) flight.result.cancel()
        }
    }

    private class Flight(val result: Deferred<AppResult<Unit>>) {
        var waiters = 1
        var closing = false
    }
}
