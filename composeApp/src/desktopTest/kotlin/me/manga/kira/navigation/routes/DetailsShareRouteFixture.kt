package me.manga.kira.navigation.routes

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.manga.kira.navigation.Screen
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.details.DetailsViewModel
import me.manga.kira.ui.theme.KiraTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Isolated real adapters and a retained navigation-entry VM; never boots the app/data graph. */
internal class DetailsShareRouteFixture(
    private val urlOnly: Boolean,
) {
    val source = DetailsShareViewModelFixture()
    val shares = mutableListOf<Pair<String, String>>()
    val createdViewModels = mutableListOf<DetailsViewModel>()
    var mounted by mutableStateOf(true)
    var compositionEpoch by mutableIntStateOf(0)
    var renderedEpoch = -1
        private set
    private val owner = DetailsShareOwner()
    private val launcher =
        object : IntentLauncher {
            override fun openUrl(url: String): Unit = error("Share must use shareText")

            override fun openPlayStorePage(packageName: String): Unit = error("Share must use shareText")

            override fun shareText(
                text: String,
                title: String,
            ) {
                shares += text to title
            }
        }
    private val app =
        koinApplication {
            allowOverride(false)
            modules(
                module {
                    single<IntentLauncher> { launcher }
                    viewModel { source.create().also { createdViewModels += it } }
                },
            )
        }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        if (!mounted) return
        KoinIsolatedContext(app) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                KiraTheme(darkTheme = false) { Navigation() }
            }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Navigation() {
        val controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = startDestination(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable<Screen.MangaDetailsRework> { entry ->
                RouteComposition { MangaDetailsReworkScreenRoute(controller, entry) }
            }
            composable<Screen.MangaDetails> { entry ->
                RouteComposition { MangaDetailsByUrlReworkScreenRoute(controller, entry) }
            }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun RouteComposition(content: @Composable () -> Unit) {
        val epoch = compositionEpoch
        // Reattach the real adapter/collector without replacing its NavBackStackEntry or VM.
        key(epoch) { content() }
        SideEffect { renderedEpoch = epoch }
    }

    private fun startDestination(): Screen {
        val seed = source.seed
        return if (urlOnly) {
            Screen.MangaDetails(mangaUrl = seed.url, api = seed.api)
        } else {
            Screen.MangaDetailsRework(
                seed.api,
                seed.language,
                seed.title,
                seed.url,
                seed.coverUrl,
                seed.rating,
                seed.genres,
            )
        }
    }

    fun close() {
        try {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        } finally {
            try {
                owner.viewModelStore.clear()
            } finally {
                app.close()
            }
        }
    }

    private class DetailsShareOwner :
        LifecycleOwner,
        ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val viewModelStore = ViewModelStore()
    }
}
