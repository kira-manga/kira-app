package me.manga.kira.presentation.complaint

import me.manga.kira.presentation.mvi.MviEffect

/** Read state renders inline; this slice performs no navigation, writes or one-shot notifications. */
sealed interface ComplaintDetailEffect : MviEffect
