package me.manga.yamiapk.navigation.routes

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.Handle403Error
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.domain.model.ReaderChapters
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.common.viewmodel.SharedChaptersViewModel
import me.manga.yamiapk.presentation.features.history.ui.viewmodel.HistoryViewModel
import me.manga.yamiapk.presentation.features.reader.ui.screens.ReaderScreen
import me.manga.yamiapk.theme.GellixFontFamily

@Composable
fun ReadingScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    sharedChaptersVm: SharedChaptersViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel()
    ) {

    val args = backStackEntry.toRoute<Screen.ChapterImagesFragment>()
    val showWebViewDalog = remember { mutableStateOf(false) }

    val historyItem = remember(args.chapterUrl) { initHistoryItem(args) }
    val startingChapter = remember(args.chapterUrl) { initStartingChapter(args) }


    LaunchedEffect(key1 = args.chapterUrl) {
        historyViewModel.insertHistory(historyItem)
    }
    // 4) Collect the Flow<State<List<ReaderChapters>>> as a Compose State
    val chaptersFlow = remember(historyItem) {

        if (args.isHome){
            sharedChaptersVm.getChaptersList(args.mangaUrl)
        }else{
        sharedChaptersVm.getChaptersByHistoryItemFlow(historyItem)}
    }
    val chaptersState: State<List<ReaderChapters>> by
    chaptersFlow.collectAsState(initial = State.Loading)

    LaunchedEffect(key1 = chaptersState) {
    }    // 5) Now branch on the current `chaptersState`
    when (chaptersState) {
        is State.Loading -> {
            // Show a full‐screen loading spinner
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is State.Error -> {
            // Extract the error message and show it
            val errorMessage = (chaptersState as State.Error).message
            if ((chaptersState as State.Error).code == 403) {
                showWebViewDalog.value = true

            }
            if (showWebViewDalog.value){
                Handle403Error(
                    state = chaptersState,
                    args.api,
                    args.chapterUrl,
                    onDismiss = {
                        showWebViewDalog.value = false
//                    coroutineScope.launch {
//                        delay(1000)
//                        mangaViewModel.getMangaHome()
//                    }
                    }
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(
                        id = R.string.failed_to_load,
                        errorMessage
                    ),
                    textAlign = TextAlign.Center,
                    fontFamily = GellixFontFamily, // Assuming this is your gellix_bold font
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error, // Matching ?attr/colorOnBackground
                    fontWeight = FontWeight.Bold, // Because you set android:textStyle="bold"
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }


        }

        is State.Success -> {
            // We got a list! Extract it.
            val chaptersList: List<ReaderChapters> = (chaptersState as State.Success).data

            // Compute startIndex based on the incoming startingChapter.url
            val startIndex: Int = remember(chaptersList, startingChapter) {
                chaptersList.indexOfFirst { it.url == startingChapter.url }
                    .takeIf { it >= 0 }
                    ?: 0
            }


            // When we do have data, show the ReaderScreen
            ReaderScreen(
                startIndex = startIndex,
                chaptersList = chaptersList,
                historyViewModel = historyViewModel,
                mangaApi = args.api,
                mangaUrl = args.mangaUrl,
                sharedChaptersVm = sharedChaptersVm,
                openChapterInWebView = { url ,api ->
                    navController.navigate(Screen.WebView(url,api))
                },
                onBackPressed = { navController.safePopBackStack() }
            )
        }
    }
}
fun initHistoryItem(args: Screen.ChapterImagesFragment): HistoryItemD {


    return  HistoryItemD(
        mangaTitle      = args.mangatitle,
    mangaUrl        = args.mangaUrl,
    mangaImageUrl   = args.mangaImgUrl,
    chapterTitle    = args.chapterNumber,
    chapterUrl      = args.chapterUrl,
    api             = args.api,
    language        = args.language,
    isDownloaded    = args.isDownload,
    localImagePaths = args.paths?: listOf(),
    mangaId         = args.mangaId
    )
}

fun initStartingChapter(args: Screen.ChapterImagesFragment): ReaderChapters {
    return ReaderChapters(
        chapterNumber = args.chapterNumber,
        chapterName = args.chapterNumber,
        isDownloaded = args.isDownload,
        url = args.chapterUrl,
        isBookmarked = args.isDownload,
        chapterId = args.chapterId,
        mangaId = args.mangaId,
        localImagePaths = args.paths ?: emptyList(),
        mangaName = args.mangatitle,
        api = args.api,
        language =args.language,
    )
}
