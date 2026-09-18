package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.presentation.mvi.MviIntent

/** Explicit reply actions only; no intent accepts a replacement parent, live handle or saved draft. */
sealed interface ComplaintReplyIntent : MviIntent {
    data class ChangeBody(
        val body: String,
    ) : ComplaintReplyIntent {
        override fun toString(): String = "ComplaintReplyIntent.ChangeBody(redacted)"
    }

    data object Submit : ComplaintReplyIntent

    data object Retry : ComplaintReplyIntent

    /** Recheck existing installation/pending metadata, never reconstruct or send a reply. */
    data object RefreshRecovery : ComplaintReplyIntent

    /** Close this live opening before asking the existing Settings owner to offer its explicit actions. */
    data object OpenRecovery : ComplaintReplyIntent

    data object Close : ComplaintReplyIntent
}
