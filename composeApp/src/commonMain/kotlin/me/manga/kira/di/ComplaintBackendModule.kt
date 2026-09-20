package me.manga.kira.di

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintBackendEndpoint
import me.manga.kira.data.complaint.backend.ComplaintBackendOwner
import me.manga.kira.data.complaint.backend.ComplaintInstallationDeletionResources
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.data.remote.complaint.ComplaintSessionEngineOwner
import me.manga.kira.data.repository.ReadOnlyComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.EditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase
import me.manga.kira.domain.usecase.feedback.CancelComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.CancelComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.CancelPreparedComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationActions
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationDeletionActions
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationRecoveryActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.domain.usecase.feedback.ConfirmComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ConfirmComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.ContinueComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ObserveComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ReconcileComplaintReportsUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintDeletionAbandonmentUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.RequestUnreadableComplaintRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.ResumeComplaintInstallationCleanupUseCase
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Composition-only factories. Constructing this descriptor performs no storage or network work. */
internal class ComplaintBackendResources(
    val credentials: () -> InstallationCredentialStore,
    val pending: () -> PendingComplaintActionStore,
    val generator: () -> InstallationCredentialMaterialGenerator,
    val engines: ComplaintBackendEngineFactories,
    val inputs: ComplaintBackendInputFactories,
)

/** Public request inputs remain lazy; neither report preparation nor deletion starts at construction. */
internal class ComplaintBackendInputFactories(
    val reports: () -> ComplaintReportInputs,
    val deletionKey: () -> String,
)

/** Five independent native-owner factories, retained as callbacks without allocating any owner. */
internal class ComplaintBackendEngineFactories(
    val enrollment: (Url) -> ComplaintSessionEngineOwner?,
    val session: (Url) -> ComplaintSessionEngineOwner?,
    val history: (Url) -> ComplaintSessionEngineOwner?,
    val mutation: (Url) -> ComplaintSessionEngineOwner?,
    val deletion: (Url) -> ComplaintSessionEngineOwner?,
)

/**
 * Internal, injectable candidate assembler for the actual consumer fixture. Never itself an
 * activation decision. The only native callers remain behind selectComplaintBackendCandidate.
 * Fatal failures are caught solely to release captured owners, then rethrown unchanged.
 */
@Suppress("TooGenericExceptionCaught")
internal fun createComplaintBackendGraph(
    baseUrl: () -> String,
    resources: ComplaintBackendResources,
    expectedDataScopeId: String? = null,
): AppResult<ComplaintBackendGraph> {
    val cleanup = mutableListOf<() -> Unit>()
    val result =
        try {
            assembleComplaintBackendGraph(baseUrl, resources, expectedDataScopeId, cleanup)
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

// Ordered early exits retain every already-created owner before any later resource can fail.
@Suppress("ReturnCount")
private fun assembleComplaintBackendGraph(
    baseUrl: () -> String,
    resources: ComplaintBackendResources,
    expectedDataScopeId: String?,
    cleanup: MutableList<() -> Unit>,
): AppResult<ComplaintBackendGraph> {
    val endpoint = ComplaintBackendEndpoint.checked(baseUrl(), expectedDataScopeId) ?: return backendGraphUnavailable()
    val enrollment = resources.engines.enrollment(endpoint.enrollmentUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, enrollment::close)
    val sessions = resources.engines.session(endpoint.sessionUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, sessions::close)
    val history = resources.engines.history(endpoint.historyUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, history::close)
    val mutation = resources.engines.mutation(endpoint.historyUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, mutation::close)
    val deletion = resources.engines.deletion(endpoint.deletionUrl) ?: return backendGraphUnavailable()
    cleanup.add(0, deletion::close)
    // Capture every owner above before reading engine properties or constructing later resources.
    val made =
        ComplaintBackendOwner.create(
            endpoint = endpoint,
            credentials = resources.credentials(),
            pending = resources.pending(),
            generator = resources.generator(),
            enrollmentEngine = enrollment.engine,
            sessionEngine = sessions.engine,
            historyEngine = history.engine,
            mutationEngine = mutation.engine,
            reportInputs = resources.inputs.reports(),
            deletionResources = ComplaintInstallationDeletionResources(deletion.engine, resources.inputs.deletionKey),
        )
    return when (made) {
        is AppResult.Failure -> made
        is AppResult.Success -> {
            cleanup.add(0, made.value::close)
            val reports = made.value.reports ?: return backendGraphUnavailable()
            val installationRecovery = made.value.installationRecovery ?: return backendGraphUnavailable()
            val installationDeletion = made.value.deletion ?: return backendGraphUnavailable()
            AppResult.Success(
                ComplaintBackendGraph(
                    made.value,
                    reports,
                    installationRecovery,
                    installationDeletion,
                    cleanup.toList(),
                ),
            )
        }
    }
}

/** Owns the data clients/work followed by the five independent native engine owners. */
@OptIn(ExperimentalAtomicApi::class)
internal class ComplaintBackendGraph(
    private val owner: ComplaintBackendOwner,
    val reports: ComplaintReportRepository,
    val installationRecovery: ComplaintInstallationRecoveryRepository,
    val installationDeletion: ComplaintInstallationDeletionRepository,
    private val cleanup: List<() -> Unit>,
) {
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    val acceptsOpenings: Boolean get() = !retired.load()
    val history: ComplaintListRepository get() = owner.history
    val details: ComplaintDetailRepository get() = owner.details
    val replies: ComplaintReplyRepository get() = checkNotNull(owner.replies)
    val edits: ComplaintEditRepository get() = checkNotNull(owner.edits)
    val ownerDeletes: ComplaintOwnerDeleteRepository get() = checkNotNull(owner.ownerDeletes)

    /** Install only in an isolated candidate Koin graph; never append over legacy-backed writes. */
    @Suppress("LongMethod") // Keep the isolated candidate's single-owner declarations together.
    fun module(): Module =
        module {
            single(createdAtStart = true) { this@ComplaintBackendGraph } onClose { it?.close() }
            complaintBackendReadBindings()
            single<ComplaintActionRepository> { ReadOnlyComplaintActionRepository() }
            complaintBackendWriteBindings()
            single<ComplaintInstallationRecoveryRepository> { get<ComplaintBackendGraph>().installationRecovery }
            single<ComplaintInstallationDeletionRepository> { get<ComplaintBackendGraph>().installationDeletion }
            factory { ReplyToComplaintUseCase(get()) }
            factory { EditComplaintUseCase(get()) }
            factory { DeleteComplaintUseCase(get()) }
            factory { ReconcileComplaintReportsUseCase(get()) }
            factory { CancelPreparedComplaintReportUseCase(get()) }
            factory { RequestComplaintReportRecoveryUseCase(get()) }
            factory { CancelComplaintReportRecoveryUseCase(get()) }
            factory { ConfirmComplaintReportRecoveryUseCase(get()) }
            factory { RequestUnreadableComplaintRecoveryUseCase(get()) }
            factory { RequestComplaintDeletionAbandonmentUseCase(get()) }
            factory { ResumeComplaintInstallationCleanupUseCase(get()) }
            factory { ObserveComplaintInstallationDeletionUseCase(get()) }
            factory { RequestComplaintInstallationDeletionUseCase(get()) }
            factory { CancelComplaintInstallationDeletionUseCase(get()) }
            factory { ConfirmComplaintInstallationDeletionUseCase(get()) }
            factory { ContinueComplaintInstallationDeletionUseCase(get()) }
            factory {
                ComplaintReportRecoveryActions(
                    reconcile = get(),
                    cancelPrepared = get(),
                    requestRecovery = get(),
                    cancelRecovery = get(),
                    confirmRecovery = get(),
                )
            }
            registerInstallationActionGroups()
            viewModel { parameters ->
                SettingsFeedbackViewModel(
                    actions = get(),
                    recoveryActions = get(),
                    observeUserComplaints = get(),
                    installationActions = get(),
                    entry = parameters.getOrNull<SettingsFeedbackEntry>() ?: SettingsFeedbackEntry.General,
                )
            }
            viewModel {
                ComplaintViewModel(
                    observeUserComplaints = get(),
                    replyToComplaint = get(),
                    editComplaint = get(),
                    deleteComplaint = get(),
                )
            }
        }

    /** Retires new route work before process teardown, without claiming any native child has drained. */
    fun retireAccess() { retired.store(true) }

    fun close() {
        retireAccess()
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            check(closeBackendGraphResources(cleanup)) { "Complaint backend graph close failed" }
        }
    }
}

/** Factory declarations only; resolution stays lazy in this same isolated owner graph. */
private fun Module.registerInstallationActionGroups() {
    factory {
        ComplaintInstallationRecoveryActions(
            requestUnreadable = get(),
            requestDeletionAbandonment = get(),
            resumeCleanup = get(),
        )
    }
    factory {
        ComplaintInstallationDeletionActions(
            observe = get(),
            request = get(),
            cancel = get(),
            confirm = get(),
            continueDeletion = get(),
        )
    }
    factory { ComplaintInstallationActions(recovery = get(), deletion = get()) }
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

internal fun backendGraphUnavailable(): AppResult.Failure =
    AppResult.Failure(
        AppError.Platform.FeatureUnavailable("complaint_backend"),
    )
