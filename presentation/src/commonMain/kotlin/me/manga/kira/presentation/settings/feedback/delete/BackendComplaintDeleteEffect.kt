package me.manga.kira.presentation.settings.feedback.delete

import me.manga.kira.presentation.mvi.MviEffect

/** Emitted only after local work drains. Never carries a target, handle or recovery authority. */
sealed interface BackendComplaintDeleteEffect : MviEffect {
    data object Closed : BackendComplaintDeleteEffect
    data object OpenRecovery : BackendComplaintDeleteEffect
}
