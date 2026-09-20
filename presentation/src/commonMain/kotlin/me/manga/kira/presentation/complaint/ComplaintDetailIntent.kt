package me.manga.kira.presentation.complaint

import me.manga.kira.presentation.mvi.MviIntent

/** Explicit read selection/refresh/dismissal only; no row, prose, tag or live mutation handle. */
sealed interface ComplaintDetailIntent : MviIntent {
    data class Select(
        val id: String,
    ) : ComplaintDetailIntent {
        override fun toString(): String = "ComplaintDetailIntent.Select(redacted)"
    }

    data object Retry : ComplaintDetailIntent

    data object Close : ComplaintDetailIntent
}
