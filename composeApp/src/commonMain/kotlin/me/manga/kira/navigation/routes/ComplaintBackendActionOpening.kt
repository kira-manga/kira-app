package me.manga.kira.navigation.routes

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteViewModel
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditViewModel
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyTarget
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyViewModel
import me.manga.kira.ui.complaint.isKnownBackendNoticeKey
import org.koin.core.Koin

/** Opening selection only. No verb here prepares, dispatches, retries or reconstructs a live request. */
internal enum class ComplaintBackendAction { REPLY, EDIT, DELETE }

/** Typed renderers retain only their own VM, never navigation arguments or a shared graph owner. */
internal sealed interface ComplaintBackendActionPanel {
    class Reply(val viewModel: ComplaintReplyViewModel) : ComplaintBackendActionPanel

    class Edit(val viewModel: BackendComplaintEditViewModel) : ComplaintBackendActionPanel

    class Delete(val viewModel: BackendComplaintDeleteViewModel) : ComplaintBackendActionPanel
}

/** One action's store. Its RememberObserver host owns forgetting/abandonment and drained handoffs. */
@Suppress("TooGenericExceptionCaught") // A failed construction must release this store, not the graph.
internal class ComplaintBackendActionOpening(
    candidate: Koin,
    action: ComplaintBackendAction,
    target: ComplaintDetail.Owned,
) {
    private val store = ViewModelStore()
    private var closed = false
    val panel: ComplaintBackendActionPanel

    init {
        try {
            check(action.accepts(target)) { "Complaint action target unavailable" }
            checkComplaintBackendActionBindings(candidate)
            val provider = ViewModelProvider.create(store, actionFactory(candidate, target))
            panel =
                when (action) {
                    ComplaintBackendAction.REPLY ->
                        ComplaintBackendActionPanel.Reply(provider[ComplaintReplyViewModel::class])
                    ComplaintBackendAction.EDIT ->
                        ComplaintBackendActionPanel.Edit(provider[BackendComplaintEditViewModel::class])
                    ComplaintBackendAction.DELETE ->
                        ComplaintBackendActionPanel.Delete(provider[BackendComplaintDeleteViewModel::class])
                }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        store.clear()
    }

    override fun toString(): String = "ComplaintBackendActionOpening(redacted)"
}

/** Verify every write/recovery/read port belongs to this same candidate before resolving any VM. */
internal fun checkComplaintBackendActionBindings(candidate: Koin) {
    val graph = candidate.get<ComplaintBackendGraph>()
    check(
        candidate.get<ComplaintListRepository>() === graph.history &&
            candidate.get<ComplaintDetailRepository>() === graph.details &&
            candidate.get<ComplaintReportRepository>() === graph.reports &&
            candidate.get<ComplaintReplyRepository>() === graph.replies &&
            candidate.get<ComplaintEditRepository>() === graph.edits &&
            candidate.get<ComplaintOwnerDeleteRepository>() === graph.ownerDeletes &&
            candidate.get<ComplaintInstallationRecoveryRepository>() === graph.installationRecovery &&
            candidate.get<ComplaintInstallationDeletionRepository>() === graph.installationDeletion,
    ) { "Complaint candidate action binding differs" }
}

/** Shape eligibility only; the existing producer still checks every target/tag/installation fence. */
internal fun ComplaintBackendAction.accepts(target: ComplaintDetail.Owned): Boolean =
    when (this) {
        ComplaintBackendAction.REPLY -> ComplaintReplyTarget.capture(target) != null
        ComplaintBackendAction.EDIT, ComplaintBackendAction.DELETE ->
            when (val row = target.item) {
                is ComplaintOwnerRow.NoticeReply -> row.isContractRecognized && isKnownBackendNoticeKey(row.noticeKey)
                is ComplaintOwnerRow.Content -> row.isContractRecognized
                else -> false
            }
    }

private fun actionFactory(
    candidate: Koin,
    target: ComplaintDetail.Owned,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            ComplaintReplyViewModel(
                candidate.get(), candidate.get(), candidate.get(), ComplaintReplyTarget.capture(target),
            )
        }
        initializer { BackendComplaintEditViewModel(candidate.get(), target.item) }
        initializer { BackendComplaintDeleteViewModel(candidate.get(), target.item) }
    }
