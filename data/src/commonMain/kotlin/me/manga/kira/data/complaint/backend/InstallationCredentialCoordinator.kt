package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.PendingDeletion
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ServerTerminalFact
import me.manga.kira.data.complaint.backend.InstallationSessionManager.ReportSessionPublication
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.platform.storage.CleanupMarkerCreateResult
import me.manga.kira.platform.storage.CleanupMarkerReadResult
import me.manga.kira.platform.storage.CleanupMarkerRemoveResult
import me.manga.kira.platform.storage.CredentialCleanupMarker
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingClearResult
import me.manga.kira.platform.storage.PendingComplaintActionStore

/**
 * Single-process lifecycle; the named enrollment operation alone adds bounded bootstrap/HTTP.
 * Every call is mutex serialized; cancellation/unknown exceptions propagate without destructive finally work.
 * Startup calls resumeCleanup before admission. Repository adapters must map results to AppResult, not prose.
 */
@Suppress("TooManyFunctions")
class InstallationCredentialCoordinator(
    private val credentials: InstallationCredentialStore,
    private val pending: PendingComplaintActionStore,
) {
    private val mutex = Mutex()
    private var reconciliationIssuer = ReconciliationIssuer()
    private var confirmation: Confirmation? = null
    private val historyReads = mutableSetOf<ComplaintHistoryWork>()
    private val reports = ComplaintReportActionBindings(pending)
    private val deletions = InstallationDeletionBindings(credentials, pending)

    /** Only an explicit initial candidate may fill a proven-empty store; an existing winner is reread. */
    suspend fun admit(candidate: InstallationCredentialRecord? = null): Outcome<Permit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            pending.requireEmptyPending()
            val record =
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> credentials.createCoordinated(candidate)
                    is CredentialReadResult.Present -> read.record
                    is InstallationStorageFailure -> fail(read)
                }
            active(record)
            Permit(record)
        }

    /** Rechecks and applies while locked. The callback must be bounded, non-suspending and perform no I/O. */
    suspend fun applyIfCurrent(
        permit: Permit,
        apply: () -> Unit,
    ): Outcome<Unit> =
        mutex.serialized {
            admission(permit)
            apply()
        }

    /** Existing-identity observation only; ordinary admission still refuses every nonempty inventory. */
    internal suspend fun beginReconciliation(): Outcome<ReconciliationPermit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = credentials.coordinationRecord().also(::active)
            ReconciliationPermit(record, pending.reconciliationSnapshot(record), reconciliationIssuer)
        }

    /** Exact read-only recheck; callback is bounded, non-suspending, and performs no I/O or dispatch. */
    internal suspend fun applyReconciliationIfCurrent(
        permit: ReconciliationPermit,
        apply: () -> Unit,
    ): Outcome<Unit> =
        mutex.serialized {
            reconciliationAdmission(permit)
            apply()
        }

    /** Bind and register the whole connected load before session HTTP or a missing-store fallback. */
    internal suspend fun beginHistory(work: ComplaintHistoryWork): Outcome<ComplaintHistoryAdmission> =
        mutex.serialized {
            currentCoroutineContext().ensureActive()
            noConsent()
            credentials.requireNoCleanupMarker()
            val binding =
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> {
                        pending.requireEmptyPending()
                        ComplaintHistoryAdmission.Missing(reconciliationIssuer)
                    }
                    is CredentialReadResult.Present -> {
                        val record = read.record.also(::active)
                        ComplaintHistoryAdmission.Existing(
                            ReconciliationPermit(record, pending.reconciliationSnapshot(record), reconciliationIssuer),
                        )
                    }
                    is InstallationStorageFailure -> fail(read)
                }
            currentCoroutineContext().ensureActive()
            if (!work.isCurrent()) refuse(Block.STALE_BINDING)
            historyReads += work
            binding
        }

    /** Cancellation-safe release only after session/enrollment/all pages and their response cleanup. */
    internal suspend fun finishHistory(work: ComplaintHistoryWork) {
        withContext(NonCancellable) { mutex.withLock { historyReads -= work } }
    }

    /** Every bound session outcome, including a never-claimed failure, observes the original work/epoch. */
    internal suspend fun checkHistorySession(
        permit: ReconciliationPermit,
        work: ComplaintHistoryWork,
    ): Outcome<Unit> =
        mutex.serialized {
            currentCoroutineContext().ensureActive()
            reconciliationAdmission(permit)
            currentCoroutineContext().ensureActive()
            historyWorkAdmission(work)
        }

    /** Existing ACTIVE identity only. This registers the whole report/status work before session I/O. */
    internal suspend fun reportInventory(
        work: ReportWork,
        expected: ReconciliationPermit? = null,
    ): Outcome<ReconciliationPermit> =
        mutex.serialized {
            currentCoroutineContext().ensureActive()
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = reportOriginRecord(expected)
            val permit = ReconciliationPermit(record, pending.reconciliationSnapshot(record), reconciliationIssuer)
            currentCoroutineContext().ensureActive()
            reports.observe(permit, work)
            permit
        }

    /** Captures no unexpected inventory into an already registered work's exact batch anchor. */
    internal suspend fun beginReportAction(
        work: ReportWork,
        start: ReportStart,
        expected: ReconciliationPermit? = null,
    ): Outcome<ReportActionBinding> =
        mutex.serialized {
            currentCoroutineContext().ensureActive()
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = reportOriginRecord(expected)
            val permit = ReconciliationPermit(record, pending.reconciliationSnapshot(record), reconciliationIssuer)
            currentCoroutineContext().ensureActive()
            reports.begin(permit, work, start)
        }

    internal suspend fun checkReportSession(binding: ReportActionBinding): Outcome<Unit> =
        mutex.serialized {
            reportAdmission(binding)
        }

    /** Named, no-I/O token publication only; never a generic authenticated action callback. */
    internal suspend fun publishReportSession(
        binding: ReportActionBinding,
        publication: ReportSessionPublication,
    ): Outcome<ReportSessionResult> =
        mutex.serialized {
            reportAdmission(binding)
            publication.publish(binding)
        }

    internal suspend fun prepareReport(
        binding: ReportActionBinding,
        session: ReportSession,
        sessions: InstallationSessionManager,
    ): Outcome<ReportActionBinding> =
        mutex.serialized {
            reportAdmission(binding)
            reportSessionAdmission(binding, session, sessions)
            reports.prepare(binding, session).also {
                reportAdmission(it)
                reportSessionAdmission(it, session, sessions)
            }
        }

    /** Both CAS/readbacks and final checks precede CREATE construction; the HTTP exchange is outside the mutex. */
    internal suspend fun dispatchReport(
        binding: ReportActionBinding,
        session: ReportSession,
        sessions: InstallationSessionManager,
        http: ComplaintMutationHttp,
    ): Outcome<ReportExchange.Create> {
        val admitted = mutex.serialized { prepareReportDispatch(binding, session, sessions) }
        return when (admitted) {
            is Outcome.Success -> {
                val dispatch = admitted.value
                currentCoroutineContext().ensureActive()
                val result = http.create(dispatch.request, session.response)
                mutex.serialized {
                    reportAdmission(dispatch.binding)
                    reportSessionAdmission(dispatch.binding, session, sessions)
                    reports.receivedCreate(dispatch.binding, session, dispatch.request, result)
                }
            }
            is Outcome.Refused -> admitted
            is Outcome.StorageFailure -> admitted
            is Outcome.Invalid -> admitted
        }
    }

    /** Runs only under the existing credential mutex; no transport or session refresh occurs here. */
    private suspend fun prepareReportDispatch(
        binding: ReportActionBinding,
        session: ReportSession,
        sessions: InstallationSessionManager,
    ): ReportCreateDispatch {
        reportAdmission(binding)
        reportSessionAdmission(binding, session, sessions)
        val rebased = reports.rebasePrepared(binding, session)
        reportAdmission(rebased)
        reportSessionAdmission(rebased, session, sessions)
        val dispatched = reports.markDispatch(rebased)
        reportAdmission(dispatched)
        reportSessionAdmission(dispatched, session, sessions)
        return ReportCreateDispatch(dispatched, reports.createRequest(dispatched, session))
    }

    internal suspend fun readReportStatus(
        binding: ReportActionBinding,
        session: ReportSession,
        sessions: InstallationSessionManager,
        http: ComplaintMutationHttp,
    ): Outcome<ReportExchange.Status> {
        val admitted =
            mutex.serialized {
                reportAdmission(binding)
                reportSessionAdmission(binding, session, sessions)
                reports.statusRequest(binding)
            }
        return when (admitted) {
            is Outcome.Success -> {
                currentCoroutineContext().ensureActive()
                val result = http.status(admitted.value, session.response)
                mutex.serialized {
                    reportAdmission(binding)
                    reportSessionAdmission(binding, session, sessions)
                    reports.receivedStatus(binding, session, admitted.value, result)
                }
            }
            is Outcome.Refused -> admitted
            is Outcome.StorageFailure -> admitted
            is Outcome.Invalid -> admitted
        }
    }

    /** Authorizes one matching 401 refresh only; the session mutex and HTTP stay outside this lock. */
    internal suspend fun authorizeReportRefresh(
        exchange: ReportExchange,
        sessions: InstallationSessionManager,
    ): Outcome<Unit> =
        mutex.serialized {
            reportAdmission(exchange.binding)
            reportSessionAdmission(exchange.binding, exchange.session, sessions)
            reports.authorizeRefresh(exchange)
        }

    /** Typed request-bound terminal application is atomically fenced before the exact slot deletion. */
    internal suspend fun applyReportOutcome(
        exchange: ReportExchange,
        sessions: InstallationSessionManager,
    ): Outcome<ReportCompletion> =
        mutex.serialized {
            reportAdmission(exchange.binding)
            reportSessionAdmission(exchange.binding, exchange.session, sessions)
            reports.apply(exchange).also { reportAdmission(it.binding) }
        }

    /** Explicit unsent cancellation from an active caller, not unconditional cleanup in a canceled finally. */
    internal suspend fun cancelPreparedReport(binding: ReportActionBinding): Outcome<ReportActionBinding> =
        mutex.serialized {
            reportAdmission(binding, unsentCancellation = true)
            reports.cancelPrepared(binding).also { reportAdmission(it, unsentCancellation = true) }
        }

    internal suspend fun requestReportRecovery(binding: ReportActionBinding): Outcome<Confirmation> =
        mutex.serialized {
            reportAdmission(binding, unsentCancellation = true)
            if (binding.permit.snapshot.isEmpty) refuse(Block.RECONCILIATION_REQUIRED)
            requestRecoveryLocked(RecoveryIntent.Reset(Permit(binding.permit.record)))
        }

    /** Advance to another action in the same work only after its HTTP/response tail returned. */
    internal suspend fun releaseReportAction(binding: ReportActionBinding): Outcome<Unit> =
        mutex.serialized {
            reportAdmission(binding)
            reports.release(binding)
        }

    /** Caller invokes only after the HTTP execute/finally tail; no pending deletion or drain assertion. */
    internal suspend fun finishReport(work: ReportWork) {
        withContext(NonCancellable) { mutex.withLock { reports.finish(work) } }
    }

    /** Caller must later establish fresh-session/dispatch prerequisites; this only persists local intent. */
    suspend fun beginDeletion(
        permit: Permit,
        key: String,
    ): Outcome<PendingDeletion> =
        mutex.serialized {
            admission(permit)
            cancelBoundWork()
            PendingDeletion(credentials.replaceCoordinated(permit.record, checked(permit.record.beginDeletion(key))))
        }

    /** Allows exact continuation after restart, including when opaque normal pending work still exists. */
    suspend fun pendingDeletion(): Outcome<PendingDeletion> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            val record = credentials.coordinationRecord().also(::deleting)
            PendingDeletion(record)
        }

    internal suspend fun beginDeletionStart(work: InstallationDeletionWork): Outcome<InstallationDeletionStart> =
        mutex.serialized {
            deletionGuards()
            deletions.start(work, reconciliationIssuer)
        }

    internal suspend fun checkDeletionStart(start: InstallationDeletionStart): Outcome<Unit> =
        mutex.serialized {
            deletionGuards()
            deletions.check(start, reconciliationIssuer)
        }

    internal suspend fun commitDeletionStart(
        start: InstallationDeletionStart,
        ticket: InstallationDeletionSession,
        sessions: InstallationSessionManager,
        key: String,
    ): Outcome<InstallationDeletionBinding> =
        mutex.serialized {
            deletionGuards()
            deletions.check(start, reconciliationIssuer)
            checked(start.record.beginDeletion(key))
            if (!sessions.claimDeletionSession(start, ticket)) refuse(Block.STALE_BINDING)
            cancelNormalWork()
            deletions.commit(start, key)
        }

    internal suspend fun continueDeletion(work: InstallationDeletionWork): Outcome<InstallationDeletionBinding> =
        mutex.serialized {
            deletionGuards()
            deletions.continuation(work, reconciliationIssuer)
        }

    /** No caller-supplied result enters here: the fixed HTTP producer runs outside this mutex. */
    internal suspend fun dispatchDeletion(
        binding: InstallationDeletionBinding,
        http: InstallationDeletionHttp,
    ): Outcome<ComplaintInstallationDeletionOutcome> {
        val request = when (val admitted = prepareDeletionDispatch(binding)) {
            is Outcome.Success -> admitted.value
            is Outcome.Refused -> return admitted
            is Outcome.StorageFailure -> return admitted
            is Outcome.Invalid -> return admitted
        }
        currentCoroutineContext().ensureActive()
        val result = http.delete(request)
        return mutex.serialized {
            deletionGuards()
            deletions.check(binding, reconciliationIssuer)
            if (http.isClosed || result.request !== request) refuse(Block.STALE_BINDING)
            if (result is InstallationDeletionHttpResult.Terminal) {
                if (!binding.work.claimTerminal(request)) refuse(Block.STALE_BINDING)
                authorizedCleanup(binding.record, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED)
                ComplaintInstallationDeletionOutcome.Completed
            } else {
                deletionPending(result)
            }
        }
    }

    private suspend fun prepareDeletionDispatch(binding: InstallationDeletionBinding): Outcome<InstallationDeletionRequest> =
        mutex.serialized {
            deletionGuards()
            deletions.check(binding, reconciliationIssuer)
            InstallationDeletionRequest(binding)
        }

    /** Only a durable SERVER_TERMINAL marker resumes here; local abandonment is never remote completion. */
    internal suspend fun resumeDeletionCleanup(work: InstallationDeletionWork): Outcome<InstallationDeletionCleanup> =
        mutex.serialized {
            noConsent()
            currentCoroutineContext().ensureActive()
            if (!work.isCurrent()) refuse(Block.STALE_BINDING)
            when (val read = credentials.readCleanupMarker()) {
                CleanupMarkerReadResult.Missing -> InstallationDeletionCleanup.NONE
                is InstallationStorageFailure -> fail(read)
                is CleanupMarkerReadResult.Present -> {
                    if (read.marker.reason != CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED) {
                        refuse(Block.CLEANUP_REQUIRED)
                    }
                    if (!work.claimMarkedCleanup(read.marker)) refuse(Block.STALE_BINDING)
                    cancelNormalWork()
                    finish(read.marker)
                    InstallationDeletionCleanup.COMPLETED
                }
            }
        }

    internal suspend fun finishDeletion(work: InstallationDeletionWork) {
        withContext(NonCancellable) { mutex.withLock { deletions.finish(work) } }
    }

    private suspend fun deletionGuards() {
        noConsent()
        credentials.requireNoCleanupMarker()
    }

    /** Blocks local admission/application until this precise prompt is confirmed or canceled. */
    suspend fun requestRecovery(intent: RecoveryIntent): Outcome<Confirmation> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            requestRecoveryLocked(intent)
        }

    /** A stale prompt cannot cancel a newer one or undo a durable pending state. */
    suspend fun cancelRecovery(expected: Confirmation): Outcome<Unit> =
        mutex.serialized {
            if (confirmation !== expected) refuse(Block.STALE_CONSENT)
            confirmation = null
        }

    /** A confirmed warning is not server erasure; authorization is persisted in reason-specific order. */
    suspend fun confirmRecovery(expected: Confirmation): Outcome<Unit> =
        mutex.serialized {
            if (confirmation !== expected) refuse(Block.STALE_CONSENT)
            credentials.requireNoCleanupMarker()
            val observed = validateIntent(expected.intent)
            if (!sameObservation(observed, expected.observed)) refuse(Block.STALE_BINDING)
            confirmation = null
            when (val intent = expected.intent) {
                is RecoveryIntent.Reset -> {
                    val reset = checked(intent.permit.record.beginLocalReset())
                    val next = credentials.replaceCoordinated(intent.permit.record, reset)
                    authorizedCleanup(next, CredentialCleanupReason.USER_RESET_CONFIRMED)
                }
                RecoveryIntent.Unreadable -> finish(mark(null, CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED))
                is RecoveryIntent.Abandon ->
                    authorizedCleanup(intent.deletion.record, CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED)
            }
        }

    /** Synthetic input is not proof of a 204/410. Nonempty opaque pending cannot be terminal-qualified here. */
    suspend fun finishServerDeletion(fact: ServerTerminalFact): Outcome<Unit> =
        mutex.serialized {
            noConsent()
            credentials.requireNoCleanupMarker()
            deleting(credentials.exactRecord(fact.deletion.record))
            pending.requireEmptyPending()
            finish(mark(fact.deletion.record.localGeneration, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED))
        }

    /** Resumes durable authority, never converts DELETION_PENDING alone into permission to erase. */
    suspend fun resumeCleanup(): Outcome<Unit> = mutex.serialized { resumeCleanupLocked() }

    /** One deadline owns both fixed exchanges and all coordinator checks, including mutex wait. */
    internal suspend fun enroll(
        http: InstallationEnrollmentHttp,
        generator: InstallationCredentialMaterialGenerator,
    ): InstallationEnrollmentResult<Unit> =
        withTimeoutOrNull(InstallationEnrollmentHttp.ATTEMPT_TIMEOUT_MS) {
            mutex
                .serialized {
                    resumeCleanupLocked()
                    val attempt = InstallationEnrollmentAttempt(credentials, pending, http, generator)
                    when (val prepared = attempt.prepare()) {
                        is InstallationEnrollmentResult.Failure -> prepared
                        is InstallationEnrollmentResult.Ready -> {
                            val permit = Permit(prepared.value)
                            admission(permit)
                            val result = attempt.send(permit.record)
                            if (result is InstallationEnrollmentResult.Ready) admission(permit)
                            attempt.checkPublication(result)
                        }
                    }
                }.enrollmentResult()
        } ?: InstallationEnrollmentResult.Failed(ComplaintSessionFailure.TIMEOUT)

    /**
     * The history caller alone selects this after exact Missing admission or strict never-claimed 404.
     * Recheck that original observation under the enrollment mutex; never re-admit arbitrary new state.
     */
    internal suspend fun enrollHistory(
        binding: ComplaintHistoryAdmission,
        work: ComplaintHistoryWork,
        http: InstallationEnrollmentHttp,
        generator: InstallationCredentialMaterialGenerator,
    ): InstallationEnrollmentResult<ReconciliationPermit> =
        withTimeoutOrNull(InstallationEnrollmentHttp.ATTEMPT_TIMEOUT_MS) {
            when (val result = mutex.serialized { enrollHistoryLocked(binding, work, http, generator) }) {
                is Outcome.Success -> result.value
                is Outcome.Refused -> InstallationEnrollmentResult.LocalFailure(result)
                is Outcome.StorageFailure -> InstallationEnrollmentResult.LocalFailure(result)
                is Outcome.Invalid -> InstallationEnrollmentResult.LocalFailure(result)
            }
        } ?: InstallationEnrollmentResult.Failed(ComplaintSessionFailure.TIMEOUT)

    private suspend fun enrollHistoryLocked(
        binding: ComplaintHistoryAdmission,
        work: ComplaintHistoryWork,
        http: InstallationEnrollmentHttp,
        generator: InstallationCredentialMaterialGenerator,
    ): InstallationEnrollmentResult<ReconciliationPermit> {
        currentCoroutineContext().ensureActive()
        historyWorkAdmission(work)
        historyEnrollmentAdmission(binding)
        // Retained pending permits reconciliation reads, never same-identity enrollment.
        pending.requireEmptyPending()
        currentCoroutineContext().ensureActive()
        historyWorkAdmission(work)
        val attempt = InstallationEnrollmentAttempt(credentials, pending, http, generator)
        val record =
            when (val prepared = attempt.prepare()) {
                is InstallationEnrollmentResult.Failure -> return prepared
                is InstallationEnrollmentResult.Ready -> prepared.value
            }
        if (binding is ComplaintHistoryAdmission.Existing && !binding.permit.record.sameAs(record)) {
            refuse(Block.STALE_BINDING)
        }
        val permit = Permit(record)
        admission(permit)
        historyWorkAdmission(work)
        val result = attempt.send(record)
        if (result is InstallationEnrollmentResult.Ready) admission(permit)
        currentCoroutineContext().ensureActive()
        historyWorkAdmission(work)
        return when (val checked = attempt.checkPublication(result)) {
            is InstallationEnrollmentResult.Failure -> checked
            is InstallationEnrollmentResult.Ready -> {
                val snapshot = pending.reconciliationSnapshot(record)
                if (snapshot.size != 0) refuse(Block.RECONCILIATION_REQUIRED)
                historyWorkAdmission(work)
                InstallationEnrollmentResult.Ready(ReconciliationPermit(record, snapshot, reconciliationIssuer))
            }
        }
    }

    /** Called only by the locked history enrollment path, before inspecting/creating enrollment work. */
    private suspend fun historyEnrollmentAdmission(binding: ComplaintHistoryAdmission) {
        when (binding) {
            is ComplaintHistoryAdmission.Existing -> reconciliationAdmission(binding.permit)
            is ComplaintHistoryAdmission.Missing -> {
                if (binding.issuer !== reconciliationIssuer) refuse(Block.STALE_BINDING)
                noConsent()
                credentials.requireNoCleanupMarker()
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> Unit
                    is CredentialReadResult.Present -> refuse(Block.STALE_BINDING)
                    is InstallationStorageFailure -> fail(read)
                }
            }
        }
    }

    /**
     * The whole load is already registered; page HTTP still runs OUTSIDE the credential mutex.
     * Cancellation stops future admissions; already-admitted native work may still send bytes.
     * The independent final check, not cancellation, prevents late result publication.
     */
    internal suspend fun readHistoryPage(
        session: ComplaintHistorySession,
        sessions: InstallationSessionManager,
        work: ComplaintHistoryWork,
        http: ComplaintHistoryHttp,
        cursor: String?,
    ): AppResult<ComplaintHistoryPage> {
        val admitted =
            mutex.serialized {
                currentCoroutineContext().ensureActive()
                historyAdmission(session, sessions, work)
            }
        if (admitted !is Outcome.Success) return historyLocalFailure(admitted)
        currentCoroutineContext().ensureActive()
        val result = http.fetch(session.response, cursor)
        return when (
            val checked =
                mutex.serialized {
                    currentCoroutineContext().ensureActive()
                    historyAdmission(session, sessions, work)
                    result
                }
        ) {
            is Outcome.Success -> checked.value
            else -> historyLocalFailure(checked)
        }
    }

    internal suspend fun publishHistory(
        session: ComplaintHistorySession,
        sessions: InstallationSessionManager,
        work: ComplaintHistoryWork,
        history: ComplaintHistory.Backend,
    ): AppResult<ComplaintHistory> =
        when (
            val checked =
                mutex.serialized {
                    currentCoroutineContext().ensureActive()
                    historyAdmission(session, sessions, work)
                    if (!work.publish()) refuse(Block.STALE_BINDING)
                    history
                }
        ) {
            is Outcome.Success -> AppResult.Success(checked.value)
            else -> historyLocalFailure(checked)
        }

    private suspend fun historyAdmission(
        session: ComplaintHistorySession,
        sessions: InstallationSessionManager,
        work: ComplaintHistoryWork,
    ) {
        reconciliationAdmission(session.permit)
        historyWorkAdmission(work)
        if (!sessions.historySessionIsCurrent(session)) refuse(Block.STALE_BINDING)
    }

    private fun historyWorkAdmission(work: ComplaintHistoryWork) {
        if (work !in historyReads || !work.isCurrent()) refuse(Block.STALE_BINDING)
    }

    private suspend fun reconciliationAdmission(permit: ReconciliationPermit) {
        if (permit.issuer !== reconciliationIssuer) refuse(Block.STALE_BINDING)
        noConsent()
        credentials.requireNoCleanupMarker()
        val record = credentials.exactRecord(permit.record).also(::active)
        if (!samePending(permit.snapshot, pending.reconciliationSnapshot(record))) refuse(Block.STALE_BINDING)
    }

    /** Original identity/epoch only: normal pending transitions must use the freshly read inventory. */
    private suspend fun reportOriginRecord(expected: ReconciliationPermit?): InstallationCredentialRecord {
        if (expected != null && expected.issuer !== reconciliationIssuer) refuse(Block.STALE_BINDING)
        val record =
            if (expected == null) credentials.coordinationRecord() else credentials.exactRecord(expected.record)
        return record.also(::active)
    }

    private suspend fun reportAdmission(
        binding: ReportActionBinding,
        unsentCancellation: Boolean = false,
    ) {
        currentCoroutineContext().ensureActive()
        reconciliationAdmission(binding.permit)
        currentCoroutineContext().ensureActive()
        reports.requireCurrent(binding, unsentCancellation)
    }

    private fun reportSessionAdmission(
        binding: ReportActionBinding,
        session: ReportSession,
        sessions: InstallationSessionManager,
    ) {
        if (!session.entry.matches(binding.permit.record, binding.permit.issuer) ||
            !sessions.reportSessionIsCurrent(session)
        ) {
            refuse(Block.STALE_BINDING)
        }
    }

    private suspend fun requestRecoveryLocked(intent: RecoveryIntent): Confirmation =
        Confirmation(intent, validateIntent(intent)).also {
            cancelBoundWork()
            confirmation = it
            reconciliationIssuer = ReconciliationIssuer()
        }

    private fun cancelBoundWork() {
        cancelNormalWork()
        deletions.cancel()
    }

    private fun cancelNormalWork() {
        historyReads.forEach { it.cancel() }
        reports.cancel()
    }

    private suspend fun resumeCleanupLocked() {
        noConsent()
        when (val marker = credentials.readCleanupMarker()) {
            is CleanupMarkerReadResult.Present -> {
                cancelBoundWork()
                finish(marker.marker)
            }
            is InstallationStorageFailure -> fail(marker)
            CleanupMarkerReadResult.Missing ->
                when (val read = credentials.read()) {
                    CredentialReadResult.Missing -> pending.requireEmptyPending()
                    is InstallationStorageFailure -> fail(read)
                    is CredentialReadResult.Present ->
                        when (read.record.state) {
                            InstallationCredentialState.LOCAL_RESET_PENDING -> {
                                cancelBoundWork()
                                authorizedCleanup(read.record, CredentialCleanupReason.USER_RESET_CONFIRMED)
                            }
                            InstallationCredentialState.DELETION_PENDING -> refuse(Block.REMOTE_DELETION_PENDING)
                            InstallationCredentialState.ACTIVE -> Unit
                        }
                }
        }
    }

    private suspend fun admission(permit: Permit) {
        noConsent()
        credentials.requireNoCleanupMarker()
        pending.requireEmptyPending()
        active(credentials.exactRecord(permit.record))
    }

    private fun noConsent() {
        if (confirmation != null) refuse(Block.CONSENT_PENDING)
    }

    private suspend fun validateIntent(intent: RecoveryIntent): CredentialReadResult =
        when (intent) {
            is RecoveryIntent.Reset ->
                CredentialReadResult.Present(credentials.exactRecord(intent.permit.record).also(::active))
            is RecoveryIntent.Abandon ->
                CredentialReadResult.Present(credentials.exactRecord(intent.deletion.record).also(::deleting))
            RecoveryIntent.Unreadable -> unreadableObservation(credentials, pending)
        }

    private suspend fun clearPending() {
        when (val result = pending.clearForConfirmedRecovery()) {
            PendingClearResult.Cleared -> pending.requireEmptyPending()
            is InstallationStorageFailure -> fail(result)
        }
    }

    private suspend fun authorizedCleanup(
        record: InstallationCredentialRecord,
        reason: CredentialCleanupReason,
    ) {
        clearPending()
        finish(mark(record.localGeneration, reason))
    }

    private suspend fun mark(
        generation: Long?,
        reason: CredentialCleanupReason,
    ): CredentialCleanupMarker {
        val marker =
            checked(
                CredentialCleanupMarker.checked(InstallationCredentialRecord.SCHEMA_VERSION, generation, reason),
            )
        when (val result = credentials.createCleanupMarkerIfMissing(marker)) {
            CleanupMarkerCreateResult.Stored, CleanupMarkerCreateResult.AlreadyPresent -> matchingMarker(marker)
            is InstallationStorageFailure -> fail(result)
        }
        return marker
    }

    private suspend fun matchingMarker(expected: CredentialCleanupMarker) {
        when (val read = credentials.readCleanupMarker()) {
            is CleanupMarkerReadResult.Present ->
                if (!read.marker.sameAs(expected)) {
                    permanent(InstallationPermanentFailure.MARKER_CONFLICT)
                }
            CleanupMarkerReadResult.Missing -> permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            is InstallationStorageFailure -> fail(read)
        }
    }

    private suspend fun finish(marker: CredentialCleanupMarker) {
        matchingMarker(marker)
        if (marker.reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED) {
            when (val read = credentials.read()) {
                is CredentialReadResult.Present -> permanent(InstallationPermanentFailure.STATE_CHANGED)
                CredentialReadResult.Missing -> Unit
                is InstallationStorageFailure -> requireUnreadable(read)
            }
            clearPending()
        } else {
            pending.requireEmptyPending()
        }
        when (val result = credentials.finishMarkedCleanup(marker)) {
            CredentialDeleteResult.Deleted, CredentialDeleteResult.Missing -> absentCredential()
            CredentialDeleteResult.Stale -> refuse(Block.STALE_BINDING)
            is InstallationStorageFailure -> fail(result)
        }
        pending.requireEmptyPending()
        when (val result = credentials.removeCleanupMarker(marker)) {
            CleanupMarkerRemoveResult.Removed, CleanupMarkerRemoveResult.Missing -> credentials.requireNoCleanupMarker()
            CleanupMarkerRemoveResult.Stale -> permanent(InstallationPermanentFailure.MARKER_CONFLICT)
            is InstallationStorageFailure -> fail(result)
        }
        absentCredential()
        pending.requireEmptyPending()
    }

    private suspend fun absentCredential() {
        when (val read = credentials.read()) {
            CredentialReadResult.Missing -> Unit
            is CredentialReadResult.Present -> permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            is InstallationStorageFailure -> fail(read)
        }
    }
}

private class ReportCreateDispatch(
    val binding: ReportActionBinding,
    val request: ComplaintCreateHttpRequest,
)
