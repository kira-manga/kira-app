package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import me.manga.kira.ui.settings.feedback.SettingsFeedbackDialog

/**
 * Unselected Settings candidate. Its caller must supply the isolated candidate graph's ViewModel;
 * there is no global Koin lookup, shipping navigation registration, or legacy feedback fallback.
 */
@Composable
internal fun ComplaintBackendSettingsFeedbackRoute(
    viewModel: SettingsFeedbackViewModel,
    onClosed: () -> Unit,
) {
    SettingsFeedbackDialog(viewModel = viewModel, onClosed = onClosed)
}
