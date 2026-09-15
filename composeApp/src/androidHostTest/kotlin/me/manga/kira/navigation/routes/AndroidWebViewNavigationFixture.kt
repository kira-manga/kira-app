package me.manga.kira.navigation.routes

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.manga.kira.core.webview.AndroidWebViewController
import me.manga.kira.core.webview.AndroidWebViewPlatform
import me.manga.kira.core.webview.LocalAndroidWebViewPlatform
import me.manga.kira.core.webview.RecordingWebView
import me.manga.kira.core.webview.WEBVIEW_TEST_URL
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.core.webview.WebViewNavState
import me.manga.kira.navigation.Screen
import kotlin.test.assertEquals
import kotlin.test.assertSame

internal class AndroidWebViewNavigationFixture {
    lateinit var nav: NavHostController
    lateinit var ownerEntry: NavBackStackEntry
    lateinit var browserEntry: NavBackStackEntry
    lateinit var browserController: WebViewController
    var solve: (String, String) -> Unit = { _, _ -> error("Owner is not mounted") }
    var constructorFailure: Throwable? = UnsupportedOperationException("provider disappeared")
    var initializingController = false
    var retries = 0
    var saves = 0
    var probes = 0
    var allocations = 0
    var externalOpens = 0
    val views = mutableListOf<RecordingWebView>()

    private val platform =
        AndroidWebViewPlatform(
            probe = {
                probes++
                true
            },
            create = { context ->
                allocations++
                constructorFailure?.let { throw it }
                RecordingWebView(context).also { views += it }
            },
            openExternal = { _, _ ->
                externalOpens++
                true
            },
        )

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        val controller = rememberNavController()
        SideEffect { nav = controller }
        CompositionLocalProvider(LocalAndroidWebViewPlatform provides platform) {
            NavHost(
                navController = controller,
                startDestination = Screen.Library,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable<Screen.Library> { Owner(controller, it) }
                composable<Screen.WebView> { Browser(controller, it) }
            }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Owner(controller: NavHostController, entry: NavBackStackEntry) {
        val callback =
            rememberCloudflareChallengeSolver(
                controller,
                entry,
                onRetry = { retries++ },
                recoveryRequestId = { _, _ -> "test-recovery" },
                isAvailable = { true },
            )
        SideEffect {
            ownerEntry = entry
            solve = callback
        }
        Box(Modifier.fillMaxSize())
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Browser(navController: NavHostController, entry: NavBackStackEntry) {
        val controller =
            remember {
                if (initializingController) InitializingController() else AndroidWebViewController()
            }
        SideEffect {
            browserEntry = entry
            browserController = controller
        }
        WebViewRouteContent(navController, entry, controller) { _, _ -> saves++ }
    }

    fun open() {
        solve(WEBVIEW_TEST_URL, "test-source")
    }

    fun assertReturned(
        retryCount: Int,
        saveCount: Int,
    ) {
        assertSame(ownerEntry, nav.currentBackStackEntry)
        assertEquals(retryCount, retries)
        assertEquals(saveCount, saves)
    }

    private class InitializingController : WebViewController {
        override val state: StateFlow<WebViewNavState> =
            MutableStateFlow(
                WebViewNavState(
                    canGoBack = true,
                    canGoForward = true,
                    initialization = WebViewInitialization.Initializing,
                ),
            )

        override fun goBack() = error("Initializing history must be inert")

        override fun goForward() = error("Initializing history must be inert")

        override fun reload() = error("Initializing reload must be inert")
    }
}
