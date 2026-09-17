package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.ui.settings.feedback.SettingsFeedbackDialog
import org.koin.core.Koin

/** Candidate route lifetime only; all draft, retry, setup and recovery behavior stays in the existing VM. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun ComplaintBackendRequestHost(
    candidate: Koin,
    onOpenUrl: (String) -> Unit,
    content: @Composable (onRequest: (SettingsFeedbackEntry) -> Unit) -> Unit,
) {
    var opening by remember(candidate) { mutableStateOf<ComplaintBackendRequestOpening?>(null) }
    var attached by remember(candidate) { mutableStateOf(true) }
    DisposableEffect(candidate) {
        onDispose {
            attached = false
            val current = opening
            opening = null
            current?.close()
        }
    }
    content { entry ->
        if (attached && opening == null) opening = ComplaintBackendRequestOpening(candidate, entry)
    }
    opening?.let { current ->
        SettingsFeedbackDialog(
            viewModel = current.viewModel,
            onClosed = {
                if (attached && opening === current) {
                    opening = null
                    current.close()
                }
            },
            onOpenUrl = onOpenUrl,
        )
    }
}
