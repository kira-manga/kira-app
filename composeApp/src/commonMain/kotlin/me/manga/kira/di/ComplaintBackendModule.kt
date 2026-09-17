package me.manga.kira.di

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintBackendEndpoint
import me.manga.kira.data.complaint.backend.ComplaintBackendOwner
import me.manga.kira.data.remote.complaint.ComplaintSessionEngineOwner
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.EditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.presentation.complaint.ComplaintViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Deliberately absent from allReworkModules/platformModule and from every shipping host.
 * No Debug flag, URL, build type or fixture can grant activation. The native adapters call this
 * before reading configuration or constructing stores/engines. Changing it needs the separate
 * W05/full-W08/recovery/owner activation gates, not merely a passing read-only fixture.
 */
internal fun <T> selectComplaintBackendCandidate(
    @Suppress("UNUSED_PARAMETER") allocate: () -> AppResult<T>,
): AppResult<T> = backendGraphUnavailable()

/** Composition-only factories. Constructing this descriptor performs no storage or network work. */
internal class ComplaintBackendResources(
    val credentials: () -> InstallationCredentialStore,
    val pending: () -> PendingComplaintActionStore,
    val generator: () -> InstallationCredentialMaterialGenerator,
    val enrollmentEngine: (Url) -> ComplaintSessionEngineOwner?,
    val sessionEngine: (Url) -> ComplaintSessionEngineOwner?,
    val historyEngine: (Url) -> ComplaintSessionEngineOwner?,
)

/**
 * Internal, injectable candidate assembler for the actual consumer fixture. Never itself an
 * activation decision. The only native callers remain behind selectComplaintBackendCandidate.
 */
internal fun createComplaintBackendGraph(
    baseUrl: () -> String,
    resources: ComplaintBackendResources,
): AppResult<ComplaintBackendGraph> {
    val cleanup = mutableListOf<() -> Unit>()
    val result = try {
        assembleComplaintBackendGraph(baseUrl, resources, cleanup)
    } catch (cancelled: CancellationException) {
        closeBackendGraphResources(cleanup)
        throw cancelled
    } catch (_: Exception) {
        backendGraphUnavailable()
    } catch (failure: Throwable) {
        closeBackendGraphResources(cleanup)
        throw failure
    }
    if (result is AppResult.Failure && !closeBackendGraphResources(cleanup)) {
        return AppResult.Failure(AppError.Unexpected("complaint_backend_cleanup_failed"))
    }
    return result
}

private fun assembleComplaintBackendGraph(
    baseUrl: () -> String,
    resources: ComplaintBackendResources,
    cleanup: MutableList<() -> Unit>,
): AppResult<ComplaintBackendGraph> {
    val endpoint = ComplaintBackendEndpoint.checked(baseUrl()) ?: return backendGraphUnavailable()
    val enrollment = resources.enrollmentEngine(endpoint.enrollmentUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, enrollment::close)
    val sessions = resources.sessionEngine(endpoint.sessionUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, sessions::close)
    val history = resources.historyEngine(endpoint.historyUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, history::close)
    // Capture every owner above before reading engine properties or constructing later resources.
    val made = ComplaintBackendOwner.create(
        endpoint = endpoint,
        credentials = resources.credentials(),
        pending = resources.pending(),
        generator = resources.generator(),
        enrollmentEngine = enrollment.engine,
        sessionEngine = sessions.engine,
        historyEngine = history.engine,
    )
    return when (made) {
        is AppResult.Failure -> made
        is AppResult.Success -> {
            cleanup.add(0, made.value::close)
            AppResult.Success(ComplaintBackendGraph(made.value, cleanup.toList()))
        }
    }
}

/** Owns the data clients/work followed by the three independent native engine owners. */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintBackendGraph(
    private val owner: ComplaintBackendOwner,
    private val cleanup: List<() -> Unit>,
) {
    private val closed = AtomicBoolean(false)
    val history: ComplaintListRepository get() = owner.history

    /** Install only in an isolated candidate Koin graph; never append over legacy-backed writes. */
    fun module(): Module = module {
        single(createdAtStart = true) { this@ComplaintBackendGraph } onClose { it?.close() }
        single<ComplaintListRepository> { get<ComplaintBackendGraph>().history }
        single<ComplaintActionRepository> { ReadOnlyComplaintActionRepository() }
        factory { ObserveUserComplaintsUseCase(get()) }
        factory { ReplyToComplaintUseCase(get()) }
        factory { EditComplaintUseCase(get()) }
        factory { DeleteComplaintUseCase(get()) }
        viewModel {
            ComplaintViewModel(
                observeUserComplaints = get(),
                replyToComplaint = get(),
                editComplaint = get(),
                deleteComplaint = get(),
            )
        }
    }

    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true) && !closeBackendGraphResources(cleanup)) {
            throw IllegalStateException("Complaint backend graph close failed")
        }
    }
}

/** All owned cleanup runs, even if one owner fails; never attach platform exceptions as causes. */
private fun closeBackendGraphResources(actions: List<() -> Unit>): Boolean {
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

private fun backendGraphUnavailable(): AppResult.Failure =
    AppResult.Failure(AppError.Platform.FeatureUnavailable("complaint_backend"))
