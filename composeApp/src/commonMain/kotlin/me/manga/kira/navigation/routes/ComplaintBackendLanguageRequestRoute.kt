package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.manga.kira.locale.LocalAppLocale
import me.manga.kira.presentation.language.LanguageViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.ui.language.LanguageScreen
import org.koin.core.Koin

/** Explicit, unregistered candidate; language selection stays on its existing VM, request prose does not. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendLanguageRequestRoute(
    candidate: Koin,
    viewModel: LanguageViewModel,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ComplaintBackendRequestHost(candidate, onOpenUrl) { onRequest ->
        // Keep old clicks bound to this candidate host, never a latest callback for a new graph.
        val requestLanguage: (String) -> Unit = remember(candidate) {
            { subject -> onRequest(SettingsFeedbackEntry.LanguageRequest(subject)) }
        }
        LanguageScreen(
            viewModel = viewModel,
            onBack = onBack,
            onOpenUrl = onOpenUrl,
            restartHintVisible = !LocalAppLocale.isLiveLocaleSwitchSupported,
            onRequestLanguage = requestLanguage,
        )
    }
}
