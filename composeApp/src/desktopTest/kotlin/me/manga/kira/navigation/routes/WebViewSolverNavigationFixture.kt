package me.manga.kira.navigation.routes

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.core.webview.WebViewNavState
import me.manga.kira.navigation.Screen
import me.manga.kira.ui.theme.KiraTheme

@Serializable
internal data class WebViewSolverTestOwner(
    val instance: String,
)

internal class WebViewSolverNavigationFixture {
    val host = HostOwner()
    lateinit var nav: NavHostController
    lateinit var currentOwner: NavBackStackEntry
    lateinit var currentBrowser: NavBackStackEntry
    val solvers = mutableMapOf<String, (String, String) -> Unit>()
    val retries = mutableMapOf<String, Int>()
    var available = true
    var nextInitialization = WebViewInitialization.Ready
    var afterRetry: (NavBackStackEntry) -> Unit = {}
    var mounted by mutableStateOf(true)
    var redraw by mutableIntStateOf(0)
    var renderedTick = -1

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        if (!mounted) return
        CompositionLocalProvider(LocalLifecycleOwner provides host, LocalViewModelStoreOwner provides host) {
            KiraTheme(darkTheme = false) { Navigation() }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Navigation() {
        val controller = rememberNavController()
        val tick = redraw
        SideEffect {
            nav = controller
            renderedTick = tick
        }
        NavHost(
            navController = controller,
            startDestination = WebViewSolverTestOwner("first"),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable<WebViewSolverTestOwner> { Owner(controller, it) }
            composable<Screen.WebView> { Browser(controller, it) }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Owner(controller: NavHostController, entry: NavBackStackEntry) {
        val callback =
            rememberCloudflareChallengeSolver(
                controller,
                entry,
                onRetry = {
                    retries[entry.id] = (retries[entry.id] ?: 0) + 1
                    afterRetry(entry)
                },
                recoveryRequestId = { _, _ -> "test-recovery" },
                isAvailable = { available },
            )
        SideEffect {
            currentOwner = entry
            solvers[entry.id] = callback
        }
        Box(Modifier.fillMaxSize())
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Browser(controller: NavHostController, entry: NavBackStackEntry) {
        val browser = remember { StubController(nextInitialization) }
        SideEffect { currentBrowser = entry }
        // No KCEF initialization: the untouched Desktop actual renders its startup placeholder.
        WebViewRouteContent(controller, entry, browser) { _, _ -> }
    }

    fun solve(owner: NavBackStackEntry = currentOwner) {
        requireNotNull(solvers[owner.id]).invoke(TEST_URL, "test-source")
    }

    fun manualBrowser() {
        nav.navigate(Screen.WebView(TEST_URL, "test-source"))
    }

    fun close() {
        host.lifecycle.currentState = Lifecycle.State.DESTROYED
        host.viewModelStore.clear()
    }

    class HostOwner :
        LifecycleOwner,
        ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val viewModelStore = ViewModelStore()
    }

    private class StubController(
        initialization: WebViewInitialization,
    ) : WebViewController {
        override val state = MutableStateFlow(WebViewNavState(initialization = initialization))

        override fun goBack() = Unit

        override fun goForward() = Unit

        override fun reload() = Unit
    }

    private companion object {
        const val TEST_URL = "https://source.example/challenge"
    }
}
