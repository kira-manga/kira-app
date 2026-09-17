package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.PendingComplaintActionStore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Owns borrowing clients/read work only. Engine ownership stays with the composition root. */
@OptIn(ExperimentalAtomicApi::class)
class ComplaintBackendOwner private constructor(
    val history: ComplaintListRepository,
    private val closeActions: List<() -> Unit>,
) {
    private val closed = AtomicBoolean(false)

    /** No credential/pending writes or deletion, and no synchronous native-drain assertion. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true) && !closeEvery(closeActions)) {
            throw IllegalStateException("Complaint backend close failed")
        }
    }

    companion object {
        fun create(
            endpoint: ComplaintBackendEndpoint,
            credentials: InstallationCredentialStore,
            pending: PendingComplaintActionStore,
            generator: InstallationCredentialMaterialGenerator,
            enrollmentEngine: HttpClientEngine,
            sessionEngine: HttpClientEngine,
            historyEngine: HttpClientEngine,
        ): AppResult<ComplaintBackendOwner> {
            if (enrollmentEngine === sessionEngine || enrollmentEngine === historyEngine || sessionEngine === historyEngine) {
                return historyUnavailable()
            }
            val close = mutableListOf<() -> Unit>()
            return try {
                val coordinator = InstallationCredentialCoordinator(credentials, pending)
                val enrollment = InstallationEnrollmentHttp(endpoint, enrollmentEngine).also { close += it::close }
                val sessions = InstallationSessionManager(coordinator, endpoint, sessionEngine).also { close.add(0, it::close) }
                val http = ComplaintHistoryHttp(endpoint, historyEngine).also { close.add(0, it::close) }
                val loads = ComplaintHistoryLoads().also { close.add(0, it::close) }
                AppResult.Success(
                    ComplaintBackendOwner(
                        BackendComplaintHistoryRepository(coordinator, sessions, enrollment, generator, http, loads),
                        close.toList(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                closeEvery(close)
                throw cancelled
            } catch (_: Exception) {
                if (closeEvery(close)) historyUnavailable()
                else AppResult.Failure(AppError.Unexpected("complaint_backend_cleanup_failed"))
            } catch (failure: Throwable) {
                closeEvery(close)
                throw failure
            }
        }
    }
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
