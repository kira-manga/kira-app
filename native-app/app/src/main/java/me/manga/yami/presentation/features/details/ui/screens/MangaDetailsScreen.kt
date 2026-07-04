package me.manga.yamiapk.presentation.features.details.ui.screens

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.Plus18memes.imgs1
import me.manga.yamiapk.core.util.Plus18memes.imgs2
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.presentation.common.screens.ErrorScreen
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.details.domain.DialogState
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.AdultConfirmationDialog
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.MConfirmationDialog
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.firebase_cores.common.rememberFirebaseAnalytics

@Composable
fun MangaDetailsScreen(
    state: State<MangaInfo>,
    savedTitles: Set<ApiTitle>,
    onBackClick: () -> Unit,
    onMangaBookmark: (MangaInfo) -> Unit,
    onChapterClick: (ChapterItem, MangaInfo, List<ChapterItem>) -> Unit,
    onDownloadClick: () -> Unit,
    hasShownRemoveBookMark: Boolean,
    onShownRemoveBookMark: () -> Unit,
    onRetry: () -> Unit,
    onOpenInWebViewError: () -> Unit,
    onHelp: () -> Unit,
    onOpenInWebView: (String, String) -> Unit,
    isPlus18 : (List<String>,String) -> Boolean,
    buildImageRequest:(Context, String, String) -> ImageRequest
){

    when (state) {
        is State.Loading -> LoadingScreen()

        is State.Error -> ErrorScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
            ,
            message =state.message,
            onRetry = onRetry,
            onOpenInBrowser = onOpenInWebViewError,
            onHelp = onHelp,
            onBack = onBackClick
        )
        is State.Success -> {
            var dialogState by remember {if (isPlus18(state.data.genres,state.data.api)) mutableStateOf(DialogState.AdultWarning) else mutableStateOf(DialogState.None) }

            val firebaseAnalytics = rememberFirebaseAnalytics()
            LaunchedEffect(state.data.title) {
                val params = Bundle().apply {
                    putString("manga_api", state.toData()?.api)
                    putString("manga_title", state.toData()?.title)
                    putString("source_screen", "home") // optional context
                }
                firebaseAnalytics.logEvent("manga_open", params)
            }
            when (dialogState) {
                DialogState.AdultWarning -> {
                    AdultConfirmationDialog(
                        onConfirm = {
                            // Move to the first MConfirmation
                            dialogState = DialogState.MStep1
                        },
                        onDismiss = {
                            onBackClick()
                        }
                    )
                }
                DialogState.MStep1 -> {
                    MConfirmationDialog(
                        images = imgs1,
                        showContinue = true,
                        onConfirm = {
                            // Move to the second MConfirmation
                            dialogState = DialogState.MStep2
                        },
                        onDismiss = {
                            // If user cancels here, go back entirely
                            onBackClick()
                        }
                    )
                }
                DialogState.MStep2 -> {
                    MConfirmationDialog(
                        images = imgs2,
                        showContinue = false,
                        onConfirm = {
                            onBackClick()

                            /* never called, since showContinue=false */ },
                        onDismiss = {
                            // Now that the second dialog is dismissed, show content
                            onBackClick()
                        }
                    )
                }
                DialogState.None -> {
                    DetailsContent(
                        manga = state.data,
                        savedTitles = savedTitles,
                        onBackClick = onBackClick,
                        hasShownRemoveBookMark = hasShownRemoveBookMark,
                        onMangaBookmark = onMangaBookmark,
                        onChapterClick = onChapterClick,
                        onDownloadClick = onDownloadClick,
                        onShownRemoveBookMark = onShownRemoveBookMark,
                        onOpenInWebView = onOpenInWebView,
                        buildImageRequest = buildImageRequest
                    )
                }
            }
        }
    }
}