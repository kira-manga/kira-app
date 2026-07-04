package me.manga.yamiapk.navigation

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.manga.yamiapk.R
import me.manga.yamiapk.ad_mob.AdViewModel
import me.manga.yamiapk.core.storage.PrefsDelegate
import me.manga.yamiapk.navigation.double_click.HomeTabReselectedHandler
import me.manga.yamiapk.navigation.double_click.NavigationHandlerHolder
import me.manga.yamiapk.navigation.routes.AdminComplaintScreenRoute
import me.manga.yamiapk.navigation.routes.ComplaintScreenRoute
import me.manga.yamiapk.navigation.routes.DownloadsScreenRoute
import me.manga.yamiapk.navigation.routes.HistoryRoute
import me.manga.yamiapk.navigation.routes.HomeRoute
import me.manga.yamiapk.navigation.routes.LanguageScreenRoute
import me.manga.yamiapk.navigation.routes.LibraryMangaRoute
import me.manga.yamiapk.navigation.routes.LibraryRoute
import me.manga.yamiapk.navigation.routes.MangaDetailsRoute
import me.manga.yamiapk.navigation.routes.NotificationsRoute
import me.manga.yamiapk.navigation.routes.ReadingScreenRoute
import me.manga.yamiapk.navigation.routes.RepoSettingsScreenRoute
import me.manga.yamiapk.navigation.routes.SettingsRoute
import me.manga.yamiapk.navigation.routes.SourcesScreenRoute
import me.manga.yamiapk.navigation.routes.StatisticsRoute
import me.manga.yamiapk.navigation.routes.ThemeSelectionScreenRoute
import me.manga.yamiapk.navigation.routes.WebViewRoute
import me.manga.yamiapk.navigation.routes.WelcomeScreenRoute
import me.manga.yamiapk.navigation.routes.WhatsNewRoute
import me.manga.yamiapk.presentation.common.viewmodel.SharedChaptersViewModel
import me.manga.yamiapk.presentation.features.about.screen.AboutScreen
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel
import me.manga.yamiapk.presentation.features.whatsnew.viewmodel.WhatsNewViewModel

@Serializable
sealed class Screen(val route: String) {

    // onboarding
    @Serializable
     object  Welcome : Screen("me.manga.yamiapk.navigation.Screen.Welcome")
    @Serializable
     object  Theme : Screen("me.manga.yamiapk.navigation.Screen.Theme")
    @Serializable
     object  Sources : Screen("me.manga.yamiapk.navigation.Screen.Sources")
    @Serializable
     object  Home : Screen("me.manga.yamiapk.navigation.Screen.Home")
    @Serializable
     object  Library : Screen("me.manga.yamiapk.navigation.Screen.Library")
    @Serializable
     object  History : Screen("me.manga.yamiapk.navigation.Screen.History")
    @Serializable
     object  Updates : Screen("me.manga.yamiapk.navigation.Screen.Updates")
    @Serializable
     object  Setting : Screen("me.manga.yamiapk.navigation.Screen.Setting")
    @Serializable
     object  Statistics : Screen("me.manga.yamiapk.navigation.Screen.Statistics")
    @Serializable
    data class WhatsNewScreen(
        val isFirstOpen: Boolean = false
    ) : Screen("me.manga.yamiapk.navigation.Screen.WhatsNewScreen")

    @Serializable
    data class RepoSettings(
        val isFirstOpen: Boolean = false
    ) : Screen("me.manga.yamiapk.navigation.Screen.RepoSettings")
    @Serializable
     object  LanguageScreen : Screen("me.manga.yamiapk.navigation.Screen.LanguageScreen")
    @Serializable
     object  DownloadsScreen : Screen("me.manga.yamiapk.navigation.Screen.DownloadsScreen")
    @Serializable
     object AboutScreen : Screen("me.manga.yamiapk.navigation.Screen.AboutScreen")

    @Serializable
    data class MangaDetails(
        val mangaUrl: String,
        val api: String
    ) : Screen("me.manga.yamiapk.navigation.Screen.MangaDetails")  // no bar here


    @Serializable
    data class LibraryMangaDetails(
        val mangaId: Long,
        ) : Screen("me.manga.yamiapk.navigation.Screen.LibraryMangaDetails")  // hide bar

    @Serializable
    data class ChapterImagesFragment(
        var isHome: Boolean = false,
        var api: String,
        var language: String,
        var mangaId: Long = 0,
        var chapterId: Long = 0,
        var mangatitle: String,
        var mangaUrl: String,
        var mangaImgUrl: String,
        var chapterNumber: String,
        var chapterUrl: String,
        var paths: List<String>?,
        val isDownload: Boolean,
    ) : Screen("me.manga.yamiapk.navigation.Screen.ChapterImagesFragment")

    @Serializable
    data class WebView(
        val url: String,
        val api: String,
        ) : Screen("me.manga.yamiapk.navigation.Screen.WebView")  // no bar here

    @Serializable
     object  Complaint : Screen("me.manga.yamiapk.navigation.Screen.ComplaintScreen")
    @Serializable
     object  ComplaintAdmin : Screen("me.manga.yamiapk.navigation.Screen.ComplaintAdmin")
}

@Composable
fun NavGraphV2(
    navController: NavHostController = rememberNavController(),
    onBottomBarVisibleChange: (Boolean) -> Unit,

    ) {
    val whatsNewViewModel: WhatsNewViewModel = hiltViewModel()
    val repoSettingsViewModel: RepoSettingsViewModel = hiltViewModel()
    val sharedChaptersVm: SharedChaptersViewModel = hiltViewModel()
    val downloadViewModelv2: DownloadViewModelv2 = hiltViewModel()
    val adViewModel: AdViewModel = hiltViewModel()

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current



    var firstLaunch: Boolean by PrefsDelegate(
        context = context,
        key = "first_launch",
        defaultValue = true,
    )



    // 2) Choose your start destination
    val rootStart = if (firstLaunch) Screen.Welcome else Screen.Library
    NavHost(
        navController = navController,
        startDestination = rootStart,

        ) {


        composable<Screen.Welcome> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WelcomeScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.Theme> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ThemeSelectionScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.Sources> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            SourcesScreenRoute(
                navController = navController, backStackEntry = backStackEntry,repoSettingsViewModel =repoSettingsViewModel
            )
        }

        composable<Screen.Home> { backStackEntry ->
            BackHandler(enabled = navController.previousBackStackEntry != null) {
                // Only handle back if there's a previous entry
                navController.safePopBackStack()
            }
            SideEffect { onBottomBarVisibleChange(true) }

            val listState = rememberSaveable(saver = LazyListState.Saver) {
                LazyListState()
            }
            val gridState = rememberSaveable(saver = LazyGridState.Saver) {
                LazyGridState()
            }
            val onHomeTabReselectedHandler = object : HomeTabReselectedHandler {
                override fun onHomeTabReselected() {
                    // Your action when reselected
                    CoroutineScope(Dispatchers.Main).launch {
                        listState.scrollToItem(0)
                        gridState.scrollToItem(0)

                    }
                }
            }.also { NavigationHandlerHolder.homeReselectHandler = it }
            HomeRoute(
                navController = navController,
                backStackEntry = backStackEntry,
                coroutineScope = coroutineScope,
                onHomeTabReselectedHandler = onHomeTabReselectedHandler,
                repoSettingsViewModel = repoSettingsViewModel,
                onMangaDetailsClick = { url, api, title, isSaved ->

                    if (isSaved) {

                        coroutineScope.launch {
                            val id = sharedChaptersVm.getIdByApiTitle(ApiTitle(api, title))

                            if (id != null) {
                                navController.navigate(Screen.LibraryMangaDetails(id))

                            } else {
                                navController.navigate(Screen.MangaDetails(mangaUrl = url, api))

                            }

                        }
                    } else {


                        navController.navigate(Screen.MangaDetails(mangaUrl = url, api))
                    }
                },
                onChapterClick = { chapterItem, mangaItem, chapters ->


                 sharedChaptersVm.setChaptersToReaderChaptersList(
                        chapters.reversed(),
                        mangaItem.title
                    )

                    navController.navigate(
                        Screen.ChapterImagesFragment(
                            isHome = true,
                            api = mangaItem.api,
                            language = mangaItem.language,
                            mangatitle = mangaItem.title,
                            mangaUrl = mangaItem.url,
                            mangaImgUrl = mangaItem.imageUrl,
                            chapterNumber = chapterItem.number,
                            chapterUrl = chapterItem.url,
                            isDownload = chapterItem.isDownloaded,
                            paths = listOf(),


                            )
                    )


                },
                listState =listState,
                gridState = gridState,


            )
        }

        composable<Screen.Library> { backStackEntry ->

            BackHandler(enabled = false) {
                // Don't handle back on start destination
            }
            SideEffect { onBottomBarVisibleChange(true) }

            LibraryRoute(
                navController = navController, backStackEntry = backStackEntry,
                downloadViewModel = downloadViewModelv2,
                onOpenRandomClick = { savedEntities ->
                    // convert to display items
                    val items = savedEntities.toData()
                    // pick a random one, or null if empty
                    val randomItem = items?.randomOrNull()

                    if (randomItem != null) {
                        navController.navigate(
                            Screen.LibraryMangaDetails(mangaId = randomItem.manga.id)
                        )
                    } else {
                        // show a Toast when there's nothing to pick
                        Toast
                            .makeText(context, "No manga in your library yet!", Toast.LENGTH_SHORT)
                            .show()
                    }

                },
                whatsNewViewModel = whatsNewViewModel
                ,
                onLibraryMangaClick = { mangaId ->
                    // navigate to details


                    navController.navigate(Screen.LibraryMangaDetails(mangaId = mangaId))
                }
            )
            // Library ---->> LibraryMangaDetails


        }

        composable<Screen.History> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }

            HistoryRoute(
                navController = navController, backStackEntry = backStackEntry,
                onHistoryImgClick = {

                    if (it.mangaId != 0L) {
                        coroutineScope.launch {
                            if (sharedChaptersVm.isMangaExists(it.mangaId)) {
                                navController.navigate(Screen.LibraryMangaDetails(mangaId = it.mangaId))
                            } else {

                                Toast
                                    .makeText(
                                        context,
                                        "THis Manga Is Deleted from the Libarary",
                                        Toast.LENGTH_SHORT
                                    )
                                    .show()
                            }
                        }


                    } else {

                        navController.navigate(
                            Screen.MangaDetails(
                                mangaUrl = it.mangaUrl,
                                api = it.api
                            )
                        )

                    }


                },
                onHistoryItemClick = { historyItem ->
                    // launch a coroutine so we can suspend until the load finishes
                    coroutineScope.launch {
                        if (sharedChaptersVm.isMangaExists(historyItem.mangaId) || historyItem.mangaId == 0L) {

                            sharedChaptersVm.clearChaptersList()


                            navController.navigate(
                                Screen.ChapterImagesFragment(
                                    api = historyItem.api,
                                    language = historyItem.language,
                                    mangatitle = historyItem.mangaTitle,
                                    mangaUrl = historyItem.mangaUrl,
                                    mangaImgUrl = historyItem.mangaImageUrl,
                                    chapterNumber = historyItem.chapterTitle,
                                    chapterUrl = historyItem.chapterUrl,
                                    isDownload = historyItem.isDownloaded,
                                    paths = historyItem.localImagePaths.take(100) ?: emptyList(),
                                    mangaId = historyItem.mangaId
                                )
                            )
                        } else {

                            Toast
                                .makeText(
                                    context,
                                    "THis Manga Is Deleted from the Libarary",
                                    Toast.LENGTH_SHORT
                                )
                                .show()
                        }
                    }

                }

            )
        }

        composable<Screen.Updates> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }

            NotificationsRoute(
                navController, backStackEntry,
                downloadViewModel = downloadViewModelv2,
                adViewModel = adViewModel,
                onNotificationClick = {


                    coroutineScope.launch {
                        sharedChaptersVm.getChaptersByNotificationItem(it)

                        navController.navigate(
                            Screen.ChapterImagesFragment(
                                api = it.api,
                                language = it.language,
                                mangatitle = it.mangaTitle,
                                mangaUrl = it.mangaUrl,
                                mangaImgUrl = it.mangaImageUrl,
                                chapterNumber = it.chapterNumber,
                                chapterUrl = it.chapterUrl,
                                isDownload = it.isDownloaded,
                                paths = it.localImagePaths,
                                mangaId = it.mangaId,
                                chapterId = it.chapterId
                            )
                        )

                    }
                },
                onNotificationImgClick = {
                    navController.navigate(Screen.LibraryMangaDetails(mangaId = it.mangaId))


                },


                )
        }

        composable<Screen.MangaDetails> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }

            MangaDetailsRoute(
                navController, backStackEntry,
                onChapterClick = { chpter, manga, chapters ->
                    sharedChaptersVm.setChaptersToReaderChaptersList(
                        chapters.reversed(), manga.title
                    )

                    navController.navigate(
                        Screen.ChapterImagesFragment(
                            isHome = true,
                            api = manga.api,
                            language = manga.language,
                            mangatitle = manga.title,
                            mangaUrl = manga.url,
                            mangaImgUrl = manga.imageUrl,
                            chapterNumber = chpter.number,
                            chapterUrl = chpter.url,
                            isDownload = chpter.isDownloaded,
                            paths = listOf()
                        )
                    )

                },
                onDownloadClick = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(navController.graph.id) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }


        composable<Screen.LibraryMangaDetails> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }

            LibraryMangaRoute(
                navController = navController,
                backStackEntry = backStackEntry,
                downloadViewModel = downloadViewModelv2,
                adViewModel = adViewModel,
                onChapterClick = { chapter, manga, chapters ->
                    sharedChaptersVm.setSavedToReaderChaptersList(chapters, manga.title)
                    navController.navigate(
                        Screen.ChapterImagesFragment(
                            api = manga.api,
                            language = manga.language,
                            mangatitle = manga.title,
                            mangaUrl = manga.url,
                            mangaId = manga.id,
                            mangaImgUrl = manga.imageUrl,
                            chapterNumber = chapter.number,
                            chapterUrl = chapter.url,
                            isDownload = chapter.isDownloaded,
                            paths = listOf()
                        )
                    )

                }
            )

        }

        composable<Screen.ChapterImagesFragment> { backStackEntry ->


            SideEffect { onBottomBarVisibleChange(false) }



            ReadingScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
                sharedChaptersVm = sharedChaptersVm  // <— same VM in reader

            )
        }

        composable<Screen.Setting> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            SettingsRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }
        composable<Screen.Statistics> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            StatisticsRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.WebView> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WebViewRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }


        composable<Screen.RepoSettings> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            RepoSettingsScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
                repoSettingsViewModel = repoSettingsViewModel
            )
        }
        composable<Screen.LanguageScreen> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            LanguageScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.DownloadsScreen> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            DownloadsScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.AboutScreen> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            AboutScreen(
                navController = navController, backStackEntry = backStackEntry,
                whatsNewViewModel
            ) {
                navController.safePopBackStack()
            }
        }

        composable<Screen.Complaint> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ComplaintScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }

        composable<Screen.WhatsNewScreen> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WhatsNewRoute(
                navController = navController,
                backStackEntry = backStackEntry,
                viewModel = whatsNewViewModel
            )
        }


        composable<Screen.ComplaintAdmin> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            AdminComplaintScreenRoute(
                navController = navController, backStackEntry = backStackEntry
            )
        }
    }




}
