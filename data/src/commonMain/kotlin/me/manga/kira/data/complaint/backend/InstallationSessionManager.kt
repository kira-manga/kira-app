package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

    /** Internal lease only: no caller may dispatch merely because session() returned successfully. */
    suspend fun historySession(): ComplaintHistorySessionResult = historyResult(session())

    /** The connected load supplies its original binding; success AND failure are checked with its work. */
    suspend fun historySession(
        permit: ReconciliationPermit,
        work: ComplaintHistoryWork,
    ): ComplaintHistorySessionResult =
        mutex.withLock {
            val previous = state.load()
            if (previous === SessionCacheState.Closed || !state.compareAndSet(previous, SessionCacheState.Empty)) {
                return@withLock ComplaintHistorySessionResult.Failed(ComplaintSessionResult.Failed(Failure.CLOSED))
            }
            when (val admitted = coordinator.checkHistorySession(permit, work)) {
                is Outcome.Success -> Unit
                is Outcome.Refused -> return@withLock failedHistory(admitted)
                is Outcome.StorageFailure -> return@withLock failedHistory(admitted)
                is Outcome.Invalid -> return@withLock failedHistory(admitted)
            }
            val result = obtain(permit, previous as? SessionCacheState.Cached)
            when (val checked = coordinator.checkHistorySession(permit, work)) {
                is Outcome.Success ->
                    if (state.load() === SessionCacheState.Closed) {
                        ComplaintHistorySessionResult.Failed(ComplaintSessionResult.Failed(Failure.CLOSED))
                    } else {
                        historyResult(result)
                    }
                is Outcome.Refused -> failedHistory(checked)
                is Outcome.StorageFailure -> failedHistory(checked)
                is Outcome.Invalid -> failedHistory(checked)
            }
        }

    private fun failedHistory(outcome: Outcome<Nothing>): ComplaintHistorySessionResult =
        ComplaintHistorySessionResult.Failed(ComplaintSessionResult.LocalFailure(outcome))

    private fun historyResult(result: ComplaintSessionResult): ComplaintHistorySessionResult =
        when (result) {
            is ComplaintSessionResult.Ready -> {
                val cached = state.load() as? SessionCacheState.Cached
                if (cached != null && cached.session === result.session && cached.isFresh()) {
                    ComplaintHistorySessionResult.Ready(cached.lease)
                } else {
                    ComplaintHistorySessionResult.Failed(ComplaintSessionResult.Failed(Failure.EXPIRED))
                }
            }
            else -> ComplaintHistorySessionResult.Failed(result)
        }

    fun historySessionIsCurrent(lease: ComplaintHistorySession): Boolean {
        val cached = state.load() as? SessionCacheState.Cached
        return cached?.lease === lease && lease.isFresh()
    }

    /** A late 401 cannot discard a refreshed token, and Closed never returns to Empty. */
    fun invalidateHistorySession(lease: ComplaintHistorySession) {
        val cached = state.load() as? SessionCacheState.Cached ?: return
        if (cached.lease === lease) state.compareAndSet(cached, SessionCacheState.Empty)
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
        started: TimeMark,
    ) : SessionCacheState {
        val lease = ComplaintHistorySession(permit, session, started)

        fun isFresh(): Boolean = lease.isFresh()

        override fun toString(): String = "InstallationSessionCache(redacted)"
    }
}
