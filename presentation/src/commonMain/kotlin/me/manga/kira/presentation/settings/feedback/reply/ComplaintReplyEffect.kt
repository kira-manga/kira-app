package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.presentation.mvi.MviEffect

/** Content-free handoffs, emitted only after this opening's operation has drained. */
sealed interface ComplaintReplyEffect : MviEffect {
    data object Closed : ComplaintReplyEffect

    /** Open existing SettingsFeedback recovery on the SAME candidate graph; no setup/reset is confirmed. */
    data object OpenSettingsRecovery : ComplaintReplyEffect
}
