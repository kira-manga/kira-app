@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyEffect
import me.manga.kira.ui.settings.feedback.BackendComplaintDeletePanel
import me.manga.kira.ui.settings.feedback.BackendComplaintEditPanel
import me.manga.kira.ui.settings.feedback.BackendComplaintReplyPanel
import me.manga.kira.ui.settings.feedback.SettingsFeedbackDialog

/** Existing panels only. Hidden/closed UI never removes the slot before its drained effect arrives. */
@Composable
internal fun ComplaintBackendActionHost(
    owner: ComplaintBackendActionHostOwner,
    slot: ComplaintBackendActionSlot?,
    onOpenUrl: (String) -> Unit,
) {
    when (slot) {
        is ComplaintBackendActionSlot.Action -> ActionOpeningPanel(owner, slot.opening)
        is ComplaintBackendActionSlot.Recovery ->
            SettingsFeedbackDialog(
                viewModel = slot.opening.viewModel,
                onClosed = { owner.recoveryFinished(slot.opening) },
                onOpenUrl = onOpenUrl,
            )
        null -> Unit
    }
}

@Composable
private fun ActionOpeningPanel(
    owner: ComplaintBackendActionHostOwner,
    opening: ComplaintBackendActionOpening,
) {
    when (val panel = opening.panel) {
        is ComplaintBackendActionPanel.Reply -> ReplyOpeningPanel(owner, opening, panel)
        is ComplaintBackendActionPanel.Edit ->
            BackendComplaintEditPanel(
                viewModel = panel.viewModel,
                onClosed = { owner.actionFinished(opening, openRecovery = false) },
                onOpenRecovery = { owner.actionFinished(opening, openRecovery = true) },
            )
        is ComplaintBackendActionPanel.Delete ->
            BackendComplaintDeletePanel(
                viewModel = panel.viewModel,
                onClosed = { owner.actionFinished(opening, openRecovery = false) },
                onOpenRecovery = { owner.actionFinished(opening, openRecovery = true) },
            )
    }
}

@Composable
private fun ReplyOpeningPanel(
    owner: ComplaintBackendActionHostOwner,
    opening: ComplaintBackendActionOpening,
    panel: ComplaintBackendActionPanel.Reply,
) {
    val state by panel.viewModel.state.collectAsState()
    LaunchedEffect(opening) {
        panel.viewModel.effects.collect { effect ->
            when (effect) {
                ComplaintReplyEffect.Closed -> owner.actionFinished(opening, openRecovery = false)
                ComplaintReplyEffect.OpenSettingsRecovery -> owner.actionFinished(opening, openRecovery = true)
            }
        }
    }
    BackendComplaintReplyPanel(state, panel.viewModel::submit)
}
