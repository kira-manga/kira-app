package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.sources.SourcesViewModel
import me.manga.kira.ui.sources.SourcesScreen
import org.koin.core.Koin

/** Explicit, unregistered in-settings candidate. No global Koin, shipping route swap or legacy report fallback. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendSourcesRequestRoute(
    candidate: Koin,
    viewModel: SourcesViewModel,
    onImportFromStorage: () -> Unit,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ComplaintBackendRequestHost(candidate, onOpenUrl) { onRequest ->
        SourcesScreen(
            viewModel = viewModel,
            onImportFromStorage = onImportFromStorage,
            onBack = onBack,
            onOpenUrl = onOpenUrl,
            onRequestSource = { subject -> onRequest(SettingsFeedbackEntry.SourceRequest(subject)) },
        )
    }
}
