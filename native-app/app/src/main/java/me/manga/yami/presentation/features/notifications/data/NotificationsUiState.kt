package me.manga.yamiapk.presentation.features.notifications.data

import me.manga.yamiapk.data.local.entity.ChapterNotification

data class NotificationsUiState(
    val groupedNotifications: List<Pair<Int, List<ChapterNotification>>> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)