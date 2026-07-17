package me.manga.kira.presentation.sourceaccess

import me.manga.kira.presentation.mvi.MviIntent

/** User actions on the Start Reading screen. */
sealed interface StartReadingIntent : MviIntent {
    data class OnActivationLinkChanged(
        val value: String,
    ) : StartReadingIntent

    data object OnActivate : StartReadingIntent

    data object OnImport : StartReadingIntent

    data object OnContinueToLibrary : StartReadingIntent
}
