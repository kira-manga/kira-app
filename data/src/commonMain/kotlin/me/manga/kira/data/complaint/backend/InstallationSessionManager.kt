package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure

/**
 * Dormant existing-identity session owner: one serialized refresh, memory-only tokens, no persistence mutation.
 * Results are current at checked publication only, never permission for a later mutation/status dispatch.
 * The optional time source is a test seam; production uses monotonic elapsed time, never a device-wall clock.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class InstallationSessionManager(
    private val coordinator: InstallationCredentialCoordinator,
    endpoint: ComplaintBackendEndpoint,
    engine: HttpClientEngine,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private val state = AtomicReference<SessionCacheState>(SessionCacheState.Empty)
    private val http = ComplaintSessionHttp(endpoint, engine)

    suspend fun session(): ComplaintSessionResult =
        mutex.withLock {
            val previous = state.load()
            if (previous === SessionCacheState.Closed || !state.compareAndSet(previous, SessionCacheState.Empty)) {
                return@withLock ComplaintSessionResult.Failed(Failure.CLOSED)
            }
            when (val admitted = coordinator.beginReconciliation()) {
                is Outcome.Success -> obtain(admitted.value, previous as? SessionCacheState.Cached)
                is Outcome.Refused -> ComplaintSessionResult.LocalFailure(admitted)
                is Outcome.StorageFailure -> ComplaintSessionResult.LocalFailure(admitted)
                is Outcome.Invalid -> ComplaintSessionResult.LocalFailure(admitted)
            }
        }

    /** Atomically drops the cache and prevents late publication, even if close races a refresh. */
    fun close() {
        state.exchange(SessionCacheState.Closed)
        http.close()
    }

    private suspend fun obtain(
        permit: ReconciliationPermit,
        previous: SessionCacheState.Cached?,
    ): ComplaintSessionResult =
        when {
            state.load() === SessionCacheState.Closed -> ComplaintSessionResult.Failed(Failure.CLOSED)
            previous != null && previous.permit.sameAs(permit) && previous.isFresh() -> publish(permit, previous)
            else -> {
                // Taking the mark BEFORE HTTP conservatively subtracts all request/receive elapsed time from 900s.
                val started = timeSource.markNow()
                when (val result = http.fetch(permit.record)) {
                    is ComplaintSessionResult.Ready ->
                        publish(permit, SessionCacheState.Cached(permit, result.session, started))
                    else -> result
                }
            }
        }

    private suspend fun publish(
        permit: ReconciliationPermit,
        candidate: SessionCacheState.Cached,
    ): ComplaintSessionResult {
        var result: ComplaintSessionResult = ComplaintSessionResult.Failed(Failure.EXPIRED)
        val applied =
            coordinator.applyReconciliationIfCurrent(permit) {
                if (candidate.isFresh()) {
                    result =
                        if (state.compareAndSet(SessionCacheState.Empty, candidate)) {
                            ComplaintSessionResult.Ready(candidate.session)
                        } else {
                            ComplaintSessionResult.Failed(Failure.CLOSED)
                        }
                }
            }
        return when (applied) {
            is Outcome.Success -> result
            is Outcome.Refused -> ComplaintSessionResult.LocalFailure(applied)
            is Outcome.StorageFailure -> ComplaintSessionResult.LocalFailure(applied)
            is Outcome.Invalid -> ComplaintSessionResult.LocalFailure(applied)
        }
    }
}

private sealed interface SessionCacheState {
    data object Empty : SessionCacheState

    data object Closed : SessionCacheState

    class Cached(
        val permit: ReconciliationPermit,
        val session: ComplaintSessionResponse,
        private val started: TimeMark,
    ) : SessionCacheState {
        fun isFresh(): Boolean {
            val elapsed = started.elapsedNow()
            return elapsed >= Duration.ZERO && elapsed < session.expiresInSeconds.seconds
        }

        override fun toString(): String = "InstallationSessionCache(redacted)"
    }
}
