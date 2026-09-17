package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure

/**
 * Dormant existing-identity session owner: one serialized refresh, memory-only tokens, no persistence mutation.
 * Results are current at checked publication only, never permission for a later mutation/status dispatch.
 * The optional time source is a test seam; production uses monotonic elapsed time, never a device-wall clock.
 */
@OptIn(ExperimentalAtomicApi::class)
@Suppress("TooManyFunctions")
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
        return cached?.entry === lease.entry && lease.isFresh()
    }

    /** A late 401 cannot discard a refreshed token, and Closed never returns to Empty. */
    fun invalidateHistorySession(lease: ComplaintHistorySession) {
        val cached = state.load() as? SessionCacheState.Cached ?: return
        if (cached.entry === lease.entry) state.compareAndSet(cached, SessionCacheState.Empty)
    }

    /** Report leases reuse the token entry, never a history lease's obsolete pending observation. */
    suspend fun reportSession(binding: ReportActionBinding): ReportSessionResult =
        mutex.withLock {
            when (val admitted = coordinator.checkReportSession(binding)) {
                is Outcome.Success -> Unit
                is Outcome.Refused -> return@withLock failedReport(admitted)
                is Outcome.StorageFailure -> return@withLock failedReport(admitted)
                is Outcome.Invalid -> return@withLock failedReport(admitted)
            }
            val previous = state.load()
            val result =
                if (previous === SessionCacheState.Closed || !state.compareAndSet(previous, SessionCacheState.Empty)) {
                    ReportSessionResult.Failed(ComplaintSessionResult.Failed(Failure.CLOSED))
                } else {
                    obtainReport(binding, previous as? SessionCacheState.Cached)
                }
            checkedReportResult(binding, result)
        }

    /** A failed refresh is bound too: it cannot authorize fallback under a changed action or identity. */
    private suspend fun checkedReportResult(
        binding: ReportActionBinding,
        result: ReportSessionResult,
    ): ReportSessionResult =
        when (val checked = coordinator.checkReportSession(binding)) {
            is Outcome.Success ->
                when {
                    state.load() === SessionCacheState.Closed ->
                        ReportSessionResult.Failed(ComplaintSessionResult.Failed(Failure.CLOSED))
                    result is ReportSessionResult.Ready && !reportSessionIsCurrent(result.session) ->
                        ReportSessionResult.Failed(ComplaintSessionResult.Failed(Failure.EXPIRED))
                    else -> result
                }
            is Outcome.Refused -> failedReport(checked)
            is Outcome.StorageFailure -> failedReport(checked)
            is Outcome.Invalid -> failedReport(checked)
        }

    fun reportSessionIsCurrent(lease: ReportSession): Boolean {
        val cached = state.load() as? SessionCacheState.Cached
        return cached?.entry === lease.entry && lease.entry.isFresh()
    }

    /** Exact entry identity prevents a delayed 401 from evicting a newer session. */
    fun invalidateReportSession(lease: ReportSession) {
        val cached = state.load() as? SessionCacheState.Cached ?: return
        if (cached.entry === lease.entry) state.compareAndSet(cached, SessionCacheState.Empty)
    }

    /** Atomically drops the cache and prevents late publication, even if close races a refresh. */
    fun close() {
        state.exchange(SessionCacheState.Closed)
        http.close()
    }

    private suspend fun obtainReport(
        binding: ReportActionBinding,
        previous: SessionCacheState.Cached?,
    ): ReportSessionResult {
        val permit = binding.permit
        val entry =
            if (previous != null && previous.entry.matches(permit.record, permit.issuer) && previous.isFresh()) {
                previous.entry
            } else {
                // Refresh owns only this session mutex, not the credential coordinator mutex.
                val started = timeSource.markNow()
                when (val result = http.fetch(permit.record)) {
                    is ComplaintSessionResult.Ready ->
                        InstallationSessionEntry(permit.record, permit.issuer, result.session, started)
                    else -> return ReportSessionResult.Failed(result)
                }
            }
        return when (val published = coordinator.publishReportSession(binding, ReportSessionPublication(this, entry))) {
            is Outcome.Success -> published.value
            is Outcome.Refused -> failedReport(published)
            is Outcome.StorageFailure -> failedReport(published)
            is Outcome.Invalid -> failedReport(published)
        }
    }

    /** Typed candidate only: publication is bounded, non-suspending and takes no session lock. */
    internal class ReportSessionPublication(
        private val manager: InstallationSessionManager,
        private val entry: InstallationSessionEntry,
    ) {
        fun publish(binding: ReportActionBinding): ReportSessionResult {
            if (!entry.matches(binding.permit.record, binding.permit.issuer) || !entry.isFresh()) {
                return ReportSessionResult.Failed(ComplaintSessionResult.Failed(Failure.EXPIRED))
            }
            val candidate = SessionCacheState.Cached(binding.permit, entry)
            return if (manager.state.compareAndSet(SessionCacheState.Empty, candidate)) {
                ReportSessionResult.Ready(ReportSession(entry))
            } else {
                ReportSessionResult.Failed(ComplaintSessionResult.Failed(Failure.CLOSED))
            }
        }
    }

    private suspend fun obtain(
        permit: ReconciliationPermit,
        previous: SessionCacheState.Cached?,
    ): ComplaintSessionResult =
        when {
            state.load() === SessionCacheState.Closed -> ComplaintSessionResult.Failed(Failure.CLOSED)
            previous != null && previous.entry.matches(permit.record, permit.issuer) && previous.isFresh() ->
                publish(permit, SessionCacheState.Cached(permit, previous.entry))
            else -> {
                // Taking the mark BEFORE HTTP conservatively subtracts all request/receive elapsed time from 900s.
                val started = timeSource.markNow()
                when (val result = http.fetch(permit.record)) {
                    is ComplaintSessionResult.Ready -> {
                        val entry = InstallationSessionEntry(permit.record, permit.issuer, result.session, started)
                        publish(permit, SessionCacheState.Cached(permit, entry))
                    }
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

private fun failedReport(outcome: Outcome<Nothing>): ReportSessionResult =
    ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(outcome))

private sealed interface SessionCacheState {
    data object Empty : SessionCacheState

    data object Closed : SessionCacheState

    class Cached(
        val permit: ReconciliationPermit,
        val entry: InstallationSessionEntry,
    ) : SessionCacheState {
        val session: ComplaintSessionResponse get() = entry.response
        val lease = ComplaintHistorySession(permit, entry)

        fun isFresh(): Boolean = lease.isFresh()

        override fun toString(): String = "InstallationSessionCache(redacted)"
    }
}
