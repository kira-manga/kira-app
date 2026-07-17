package me.manga.kira.presentation.sourceaccess

import me.manga.kira.presentation.mvi.MviState

/** State for the Start Reading activation surface. */
data class StartReadingState(
    val activationLink: String = "",
    val isActivating: Boolean = false,
    val invalidLink: Boolean = false,
) : MviState
