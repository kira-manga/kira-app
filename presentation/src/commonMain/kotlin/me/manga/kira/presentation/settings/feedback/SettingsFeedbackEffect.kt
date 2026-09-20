package me.manga.kira.presentation.settings.feedback

import me.manga.kira.presentation.mvi.MviEffect

/** One-shot dialog/navigation requests; UI never receives a raw reset token. */
sealed interface SettingsFeedbackEffect : MviEffect {
    /** Warn that the entire local installation/pending state is reset, and server reports are NOT erased. */
    data object ConfirmLocalReset : SettingsFeedbackEffect

    /** This VM's work is canceled and its local prompt dismissed; durable pending records remain intact. */
    data object Closed : SettingsFeedbackEffect
}
