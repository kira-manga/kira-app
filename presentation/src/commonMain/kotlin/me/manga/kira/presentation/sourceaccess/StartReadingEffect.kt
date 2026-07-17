package me.manga.kira.presentation.sourceaccess

import me.manga.kira.presentation.mvi.MviEffect

/** Navigation effects emitted by the Start Reading flow. */
sealed interface StartReadingEffect : MviEffect {
    data object ActivationSucceeded : StartReadingEffect

    data object OpenImport : StartReadingEffect

    data object ContinueToLibrary : StartReadingEffect
}
