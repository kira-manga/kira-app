package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.ui.complaint.BackendComplaintDetail
import me.manga.kira.ui.complaint.ComplaintScreen
import org.koin.core.Koin

/** Explicit isolated candidate only; actions share its owner without registering a shipping route. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendDetailRoute(
    candidate: Koin,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
) {
    // The RememberObserver owner also closes if composition is abandoned before any effect starts.
    val opening = remember(candidate) { ComplaintBackendDetailOpening(candidate) }
    val actions = remember(candidate, opening) { ComplaintBackendActionHostOwner(candidate, opening.detail) }
    val slot by actions.state.collectAsState()
    ComplaintScreen(
        viewModel = opening.history,
        onBack = {
            try {
                actions.close()
            } finally {
                opening.close()
            }
            onBack()
        },
        onBackendRowClick = { if (actions.canSelect) opening.select(it) },
    )
    if (slot == null) CandidateDetailActions(opening, actions)
    ComplaintBackendActionHost(actions, slot, onOpenUrl)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun CandidateDetailActions(
    opening: ComplaintBackendDetailOpening,
    actions: ComplaintBackendActionHostOwner,
) {
    val state by opening.detail.state.collectAsState()
    val target = state.actionTarget()
    BackendComplaintDetail(
        viewModel = opening.detail,
        onReply = actionCallback(actions, ComplaintBackendAction.REPLY, target),
        onEdit = actionCallback(actions, ComplaintBackendAction.EDIT, target),
        onDelete = actionCallback(actions, ComplaintBackendAction.DELETE, target),
    )
}

private fun actionCallback(
    owner: ComplaintBackendActionHostOwner,
    action: ComplaintBackendAction,
    target: ComplaintDetail?,
): (() -> Unit)? =
    if (target != null && owner.canOpen(action, target)) {
        { owner.open(action, target) }
    } else {
        null
    }
