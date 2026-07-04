    package me.manga.yamiapk.navigation.routes

    import android.util.Log
    import androidx.compose.runtime.Composable
    import androidx.hilt.navigation.compose.hiltViewModel
    import androidx.navigation.NavBackStackEntry
    import androidx.navigation.NavController
    import androidx.navigation.toRoute
    import me.manga.yamiapk.navigation.Screen
    import me.manga.yamiapk.navigation.safePopBackStack
    import me.manga.yamiapk.presentation.features.webview.ui.screens.WebViewComposeScreen
    import me.manga.yamiapk.presentation.features.webview.ui.viewmodel.WebViewViewModel

    @Composable
    fun WebViewRoute(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        webViewViewModel: WebViewViewModel = hiltViewModel()

    ){

        val args = backStackEntry.toRoute<Screen.WebView>()


        WebViewComposeScreen(args.api,args.url,onSaveHeaders = webViewViewModel::saveHeaders){ header,api ->


            webViewViewModel.saveHeaders(header,api)
            navController.safePopBackStack()
        }

    }
