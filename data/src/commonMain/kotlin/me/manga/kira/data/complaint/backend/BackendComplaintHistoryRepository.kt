package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator

/** The actual owner read port. No Firestore, device identifier, content persistence or mutation adapter. */
internal class BackendComplaintHistoryRepository(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val enrollment: InstallationEnrollmentHttp,
    private val generator: InstallationCredentialMaterialGenerator,
    private val http: ComplaintHistoryHttp,
    private val loads: ComplaintHistoryLoads,
) : ComplaintListRepository {
    override suspend fun loadUserComplaints(): AppResult<ComplaintHistory> =
        try {
            loads.run { work -> read(work) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError.Unexpected("complaint_history_failed"))
        }

    // These ordered fail-closed guards must complete before any registered history work is used.
    @Suppress("ReturnCount")
    private suspend fun read(work: ComplaintHistoryWork): AppResult<ComplaintHistory> {
        val cleanup = coordinator.resumeCleanup()
        if (cleanup !is Outcome.Success) return historyLocalFailure(cleanup)
        val admitted = coordinator.beginHistory(work)
        if (admitted !is Outcome.Success) return historyLocalFailure(admitted)
        return try {
            readAdmitted(admitted.value, work)
        } finally {
            coordinator.finishHistory(work)
        }
    }

    // Keep failure exits adjacent to their binding/page checks; no partial history is published.
    @Suppress("ReturnCount")
    private suspend fun readAdmitted(
        binding: ComplaintHistoryAdmission,
        work: ComplaintHistoryWork,
    ): AppResult<ComplaintHistory> {
        val initial = session(binding, work, allowEnrollment = true)
        if (initial is AppResult.Failure) return initial
        var lease = (initial as AppResult.Success).value
        val pages = ComplaintHistoryPages()
        var refreshed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = coordinator.readHistoryPage(lease, sessions, work, http, pages.cursor)
            if (result is AppResult.Failure) {
                val status = (result.error as? AppError.Network.Http)?.statusCode
                if (status != HttpStatusCode.Unauthorized.value || refreshed) return result
                refreshed = true
                sessions.invalidateHistorySession(lease)
                val renewed = session(ComplaintHistoryAdmission.Existing(lease.permit), work, allowEnrollment = false)
                if (renewed is AppResult.Failure) return renewed
                val replacement = (renewed as AppResult.Success).value
                // A reset, changed pending inventory or new identity cannot merge earlier pages
                // into a newly authenticated binding, even if both sessions were independently valid.
                if (!lease.permit.sameAs(replacement.permit)) {
                    return historyLocalFailure(Outcome.Refused(Block.STALE_BINDING))
                }
                lease = replacement
                continue
            }
            val page = (result as AppResult.Success).value
            if (!pages.accept(page)) return malformedHistory()
            if (pages.complete) {
                val published = coordinator.publishHistory(lease, sessions, work, pages.snapshot())
                if (published is AppResult.Success) pages.reportMismatches()
                return published
            }
        }
    }

    // Session and enrollment failures retain their exact admission rather than sharing a fallthrough.
    @Suppress("ReturnCount")
    private suspend fun session(
        binding: ComplaintHistoryAdmission,
        work: ComplaintHistoryWork,
        allowEnrollment: Boolean,
    ): AppResult<ComplaintHistorySession> {
        when (binding) {
            is ComplaintHistoryAdmission.Existing -> {
                val result = sessions.historySession(binding.permit, work)
                if (result is ComplaintHistorySessionResult.Ready) return AppResult.Success(result.session)
                val failure = (result as ComplaintHistorySessionResult.Failed).failure
                if (!allowEnrollment || !failure.isNeverClaimed()) return historySessionFailure(failure)
            }
            is ComplaintHistoryAdmission.Missing ->
                if (!allowEnrollment) return historyLocalFailure(Outcome.Refused(Block.MISSING))
        }
        // Recheck the original exact record/pending/epoch (or still-Missing observation) while locked.
        // The returned permit, never an arbitrary recapture, binds the mandatory follow-on session.
        val permit =
            when (val enrolled = coordinator.enrollHistory(binding, work, enrollment, generator)) {
                is InstallationEnrollmentResult.Failure -> return historyEnrollmentFailure(enrolled)
                is InstallationEnrollmentResult.Ready -> enrolled.value
            }
        return when (val authenticated = sessions.historySession(permit, work)) {
            is ComplaintHistorySessionResult.Ready -> AppResult.Success(authenticated.session)
            is ComplaintHistorySessionResult.Failed -> historySessionFailure(authenticated.failure)
        }
    }
}

private fun ComplaintSessionResult.isNeverClaimed(): Boolean =
    this is ComplaintSessionResult.HttpFailure &&
        status == HttpStatusCode.NotFound.value &&
        problem == ComplaintSessionProblem.INSTALLATION_NOT_FOUND
