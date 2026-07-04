package me.manga.yamiapk.presentation.features.notifications.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.notifications.ui.viewmodel.NotificationsViewModel


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun NotificationScreen(
    onNotificationClick: (ChapterNotification) -> Unit,
    onNotificationImgClick: (ChapterNotification) -> Unit,
    onNotificationDownloadClick: (ChapterNotification) -> Unit,
    downloadingChapters: List<Long>,
    runningChapter : ChapterDownloadEntity?,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar ={
            TopAppBarCom(
                title = stringResource(id = R.string.title_notifications),
                actions = {
                    IconButton(
                        onClick = { viewModel.deleteAll() },
                        enabled = uiState.groupedNotifications.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.contentDescription_delete_all))
                    }
                    IconButton(
                        onClick = { viewModel.markAllAsRead() },
                        enabled = uiState.groupedNotifications.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.contentDescription_mark_all_as_read))
                    }
                })
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }

            if (uiState.isLoading) {
                LoadingScreen()
            } else {
                LazyColumn (modifier = Modifier.animateContentSize()){
                    uiState.groupedNotifications.forEach { (labelRes, notifications) ->
                        item {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        items(
                            items = notifications,
                            key = { it.id }
                        ) { notification ->
                            // swipe-to-dismiss
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { newValue ->
                                    when (newValue) {
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            viewModel.markAsRead(notification.chapterId)
                                        }
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            // Delete with undo option
                                            viewModel.deleteWithUndo(notification)
                                            scope.launch {
                                                val resultDeferred = async {
                                                    snackbarHostState.showSnackbar(
                                                        message = "Notification deleted",
                                                        actionLabel = "Undo",
                                                        withDismissAction = true,
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }


                                                val result = resultDeferred.await()
                                                Log.i("asfadfasdgasfgfdgdgdfgdfsgdf",result.toString())
                                                when (result) {


                                                    SnackbarResult.ActionPerformed -> viewModel.undoDelete()
                                                    SnackbarResult.Dismissed -> viewModel.confirmDelete()
                                                }
                                            }


                                        }
                                        else -> {}
                                    }
                                    false
                                }
                            )

                            SwipeToDismissBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .animateContentSize(),
                                state = dismissState,
                                backgroundContent = {
                                    val bgColor by animateColorAsState(
                                        targetValue = when (dismissState.dismissDirection) {
                                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.background
                                        },
                                        animationSpec = TweenSpec(
                                            durationMillis = 300,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(bgColor)
                                            .padding(horizontal = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                            Icon(
                                                imageVector = Icons.Outlined.Done,
                                                contentDescription = "Mark as read",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }

                                        if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    }
                                },
                                enableDismissFromStartToEnd = true,
                                enableDismissFromEndToStart = true
                            ) {
                                NotificationItems(
                                    notification = notification,
                                    onNotificationClick = {
                                        viewModel.markAsRead(it.chapterId)
                                        onNotificationClick(it)
                                    },
                                    onNotificationImgClick = onNotificationImgClick,
                                    onNotificationDownloadClick = onNotificationDownloadClick,
                                    downloadingChapters = downloadingChapters,
                                    runningChapter = runningChapter
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}