package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.PendingComplaintActionStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Owns borrowing clients and separate history/report work lanes. Engines stay with the composition root. */
@OptIn(ExperimentalAtomicApi::class)
class ComplaintBackendOwner private constructor(
    val history: ComplaintListRepository,
    internal val feedback: BackendFeedbackRepository?,
    /** Present only when this owner has both a distinct mutation engine and inert report input suppliers. */
    val reports: ComplaintReportRepository?,
    private val closeActions: List<() -> Unit>,
) {
    private val closed = AtomicBoolean(false)

    /** No credential/pending writes or deletion, and no synchronous native-drain assertion. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            check(closeEvery(closeActions)) { "Complaint backend close failed" }
        }
    }

    companion object {
        // Keep distinct borrowed engines explicit. Fatal failures only trigger cleanup, then are rethrown.
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun create(
            endpoint: ComplaintBackendEndpoint,
            credentials: InstallationCredentialStore,
            pending: PendingComplaintActionStore,
            generator: InstallationCredentialMaterialGenerator,
            enrollmentEngine: HttpClientEngine,
            sessionEngine: HttpClientEngine,
            historyEngine: HttpClientEngine,
            mutationEngine: HttpClientEngine? = null,
            reportInputs: ComplaintReportInputs? = null,
        ): AppResult<ComplaintBackendOwner> {
            if (!distinctBorrowedEngines(enrollmentEngine, sessionEngine, historyEngine, mutationEngine)) {
                return historyUnavailable()
            }
            val close = mutableListOf<() -> Unit>()
            return try {
                val coordinator = InstallationCredentialCoordinator(credentials, pending)
                val enrollment = InstallationEnrollmentHttp(endpoint, enrollmentEngine).also { close += it::close }
                val sessions =
                    InstallationSessionManager(coordinator, endpoint, sessionEngine).also { close.add(0, it::close) }
                val http = ComplaintHistoryHttp(endpoint, historyEngine).also { close.add(0, it::close) }
                val loads = ComplaintHistoryLoads().also { close.add(0, it::close) }
                // Production selection remains outside this optional report graph.
                val feedback =
                    mutationEngine?.let { engine ->
                        val mutation = ComplaintMutationHttp(endpoint, engine).also { close.add(1, it::close) }
                        val works = ReportWorkOwner().also { close.add(0, it::close) }
                        BackendFeedbackRepository(coordinator, sessions, mutation, works)
                    }
                val reports = createReports(coordinator, feedback, reportInputs, close)
                AppResult.Success(
                    ComplaintBackendOwner(
                        BackendComplaintHistoryRepository(coordinator, sessions, enrollment, generator, http, loads),
                        feedback,
                        reports,
                        close.toList(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                closeEvery(close)
                throw cancelled
            } catch (_: Exception) {
                if (closeEvery(close)) {
                    historyUnavailable()
                } else {
                    AppResult.Failure(AppError.Unexpected("complaint_backend_cleanup_failed"))
                }
            } catch (failure: Throwable) {
                closeEvery(close)
                throw failure
            }
        }
    }
}

/** Construct and register the optional consumer together; failure still unwinds through the owner. */
private fun createReports(
    coordinator: InstallationCredentialCoordinator,
    feedback: BackendFeedbackRepository?,
    inputs: ComplaintReportInputs?,
    close: MutableList<() -> Unit>,
): BackendComplaintReportRepository? {
    if (feedback == null || inputs == null) return null
    return BackendComplaintReportRepository(coordinator, feedback, inputs).also { close.add(0, it::close) }
}

private fun distinctBorrowedEngines(
    enrollment: HttpClientEngine,
    session: HttpClientEngine,
    history: HttpClientEngine,
    mutation: HttpClientEngine?,
): Boolean {
    val readsDistinct = enrollment !== session && enrollment !== history && session !== history
    return readsDistinct && mutation !== enrollment && mutation !== session && mutation !== history
}

/** Attempt every owned close even after failure; exceptions never escape with platform/content diagnostics. */
private fun closeEvery(actions: List<() -> Unit>): Boolean {
    var success = true
    for (close in actions) {
        try {
            close()
        } catch (_: Throwable) {
            success = false
        }
    }
    return success
}
