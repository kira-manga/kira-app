package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.manga.kira.presentation.settings.SettingsDestination
import me.manga.kira.presentation.settings.SettingsViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.ui.settings.SettingsScreen
import org.koin.core.Koin

/** Explicit, unregistered General Settings candidate; never restores the legacy feedback producer. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendGeneralSettingsRoute(
    candidate: Koin,
    viewModel: SettingsViewModel,
    onNavigate: (SettingsDestination) -> Unit,
    onOpenUrl: (String) -> Unit,
    sourceAccessActivated: Boolean = false,
    lowPowerCompressionToggleVisible: Boolean = false,
    crashDiagnosticsVisible: Boolean = false,
) {
    ComplaintBackendRequestHost(candidate, onOpenUrl) { onRequest ->
        // Capture this graph's host holders, not a latest callback that could retarget an old click.
        val requestFeedback: () -> Unit = remember(candidate) {
            { onRequest(SettingsFeedbackEntry.General) }
        }
        SettingsScreen(
            viewModel = viewModel,
            onNavigate = onNavigate,
            sourceAccessActivated = sourceAccessActivated,
            onOpenUrl = onOpenUrl,
            lowPowerCompressionToggleVisible = lowPowerCompressionToggleVisible,
            crashDiagnosticsVisible = crashDiagnosticsVisible,
            onRequestFeedback = requestFeedback,
        )
    }
}
