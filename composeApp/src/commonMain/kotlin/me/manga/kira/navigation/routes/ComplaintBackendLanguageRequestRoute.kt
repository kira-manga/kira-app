package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
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
        LanguageScreen(
            viewModel = viewModel,
            onBack = onBack,
            onOpenUrl = onOpenUrl,
            restartHintVisible = !LocalAppLocale.isLiveLocaleSwitchSupported,
            onRequestLanguage = { subject -> onRequest(SettingsFeedbackEntry.LanguageRequest(subject)) },
        )
    }
}
