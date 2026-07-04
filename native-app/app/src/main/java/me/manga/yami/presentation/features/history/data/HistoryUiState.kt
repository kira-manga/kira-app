package me.manga.yamiapk.presentation.features.history.data

import me.manga.yamiapk.data.local.entity.HistoryItemD

data class HistoryUiState(
    val historyItems: List<HistoryItemD> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)