package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.presentation.sources.SourcesViewModel
import me.manga.kira.ui.sources.SourcesScreen
import org.koin.core.Koin

/** Unregistered Settings/onboarding candidate. Finish/import stay caller-owned; no legacy fallback. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming", "LongParameterList")
@Composable
internal fun ComplaintBackendSourcesRequestRoute(
    candidate: Koin,
    viewModel: SourcesViewModel,
    onImportFromStorage: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenUrl: (String) -> Unit,
    onFinish: (() -> Unit)? = null,
) {
    ComplaintBackendRequestHost(candidate, onOpenUrl) { onRequest ->
        // Capture this graph's host holders; an old click must not retarget a replacement graph.
        val requestSource: (String) -> Unit = remember(candidate) {
            { subject -> onRequest(SettingsFeedbackEntry.SourceRequest(subject)) }
        }
        SourcesScreen(
            viewModel = viewModel,
            onImportFromStorage = onImportFromStorage,
            onBack = onBack,
            onOpenUrl = onOpenUrl,
            onFinish = onFinish,
            // Activation reveals stored source choices; onboarding must not auto-enable a locale.
            onboardingLanguageTag = null,
            onRequestSource = requestSource,
        )
    }
}
