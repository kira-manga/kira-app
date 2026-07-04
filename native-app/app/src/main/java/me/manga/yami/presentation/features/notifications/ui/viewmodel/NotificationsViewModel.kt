package me.manga.yamiapk.presentation.features.notifications.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.presentation.features.notifications.data.NotificationsUiState
import me.manga.yamiapk.presentation.features.notifications.domain.NotificationRepository
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repo: NotificationRepository,
) : ViewModel() {

    private var pendingDeleteNotification: ChapterNotification? = null

    val uiState: StateFlow<NotificationsUiState> =
        repo.getGroupedNotifications()
            .map { data ->
                // on each successful emission
                NotificationsUiState(
                    groupedNotifications = data,
                    isLoading = false,
                    errorMessage = null
                )
            }
            .onStart {
                // before first data arrives, show loading
                emit(NotificationsUiState(isLoading = true))
            }
            .catch { e ->
                // if anything in the upstream flow throws
                emit(
                    NotificationsUiState(
                        groupedNotifications = emptyList(),
                        isLoading = false,
                        errorMessage = e.localizedMessage
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Companion.WhileSubscribed(5_000),
                initialValue = NotificationsUiState(isLoading = true)
            )

    fun markAsRead(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        repo.markAsRead(id)
    }

    fun markAllAsRead() = viewModelScope.launch(Dispatchers.IO) {
        repo.markAllAsRead()
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteAll()
    }

    fun delete(notification: ChapterNotification) = viewModelScope.launch {
        repo.delete(notification)
    }

    /**
     * Deletes a notification but keeps it in memory for potential undo.
     */
    fun deleteWithUndo(notification: ChapterNotification) {
        pendingDeleteNotification = notification
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(notification)
        }
    }

    /**
     * Restores the last deleted notification if undo is requested.
     */
    fun undoDelete() {
        pendingDeleteNotification?.let { notification ->
            viewModelScope.launch(Dispatchers.IO) {
                repo.restore(notification)
            }
            pendingDeleteNotification = null
        }
    }

    /**
     * Confirms the deletion by clearing the pending notification.
     * Called when the Snackbar timeout expires without undo.
     */
    fun confirmDelete() {

        pendingDeleteNotification = null
    }
}