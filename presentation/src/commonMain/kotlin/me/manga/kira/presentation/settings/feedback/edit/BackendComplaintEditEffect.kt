package me.manga.kira.presentation.settings.feedback.edit

import me.manga.kira.presentation.mvi.MviEffect

/** Content-free handoffs emitted only after this opening's canceled operation has finished. */
sealed interface BackendComplaintEditEffect : MviEffect {
    data object Closed : BackendComplaintEditEffect

    /** Caller opens existing same-graph Settings recovery; no draft, tag, live or pending token travels. */
    data object OpenRecovery : BackendComplaintEditEffect
}
