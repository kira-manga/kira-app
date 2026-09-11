package me.manga.kira.navigation.routes

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.di.legacySharedViewModelsModule
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.repository.WhatsNewRepository
import me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase
import me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase
import me.manga.kira.navigation.Screen
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.ui.theme.KiraTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel as GateViewModel
import me.manga.kira.presentation.whatsnew.WhatsNewViewModel as DestinationViewModel

/** Only the real redirect/destination adapters: no Library data graph, HTTP or app bootstrap. */
internal class WhatsNewNavigationFixture(
    seenVersion: String = "",
) {
    private val settings = MapSettings()
    val owner = HostOwner()
    val prefs = SharedPrefsHelper(settings).apply { putString(SEEN_KEY, seenVersion) }
    val repository = RecordingRepository()
    var mounted by mutableStateOf(true)
    var redraw by mutableIntStateOf(0)
    var renderedTick = -1
    var notesMounted = false
        private set
    lateinit var controller: NavHostController
    lateinit var libraryEntry: NavBackStackEntry
    lateinit var libraryGate: GateViewModel

    private val app =
        koinApplication {
            allowOverride(false)
            modules(
                legacySharedViewModelsModule,
                module {
                    single { prefs }
                    single { DataStoreHelper(settings) }
                    single<AppVersionProvider> { FixedVersion }
                    single<WhatsNewRepository> { repository }
                    factory { GetWhatsNewFeaturesUseCase(get()) }
                    factory { MarkWhatsNewSeenUseCase(get()) }
                    viewModel { DestinationViewModel(get(), get()) }
                    single<IntentLauncher> { NoOpLauncher }
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
        controller = rememberNavController()
        NavHost(
            navController = controller,
            startDestination = Screen.Library,
            enterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
            exitTransition = { fadeOut(tween(TRANSITION_MILLIS)) },
            popEnterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
            popExitTransition = { fadeOut(tween(TRANSITION_MILLIS)) },
        ) {
            composable<Screen.Library> { entry -> Library(entry) }
            composable<Screen.History> { Box(Modifier.fillMaxSize()) }
            composable<Screen.WhatsNewScreen> { entry -> Notes(entry) }
            composable<Screen.WhatsNewRework> { entry -> WhatsNewReworkScreenRoute(controller, entry) }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Library(entry: NavBackStackEntry) {
        libraryEntry = entry
        libraryGate = koinViewModel(viewModelStoreOwner = entry)
        Box(Modifier.fillMaxSize()) { LibraryWhatsNewRedirect(controller, entry) }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Notes(entry: NavBackStackEntry) {
        val tick = redraw
        SideEffect { renderedTick = tick }
        DisposableEffect(entry) {
            notesMounted = true
            onDispose { notesMounted = false }
        }
        WhatsNewScreenRoute(controller, entry)
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

    class HostOwner :
        LifecycleOwner,
        ViewModelStoreOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        override val viewModelStore = ViewModelStore()
    }

    class RecordingRepository : WhatsNewRepository {
        var loads = 0
        var marks = 0
        var completedLoads = 0
            private set
        private var nextResult: CompletableDeferred<AppResult<List<WhatsNewFeature>>>? = null

        fun deferNextLoad(): CompletableDeferred<AppResult<List<WhatsNewFeature>>> =
            CompletableDeferred<AppResult<List<WhatsNewFeature>>>().also { nextResult = it }

        override suspend fun getFeatures(): AppResult<List<WhatsNewFeature>> {
            loads++
            val pending = nextResult
            nextResult = null
            val result = pending?.await() ?: AppResult.Success(emptyList())
            completedLoads++
            return result
        }

        override suspend fun markSeen() {
            // Deliberately leave prefs unseen: dismissal must rely on successful-push suppression,
            // not hide a broken entry guard behind a fast persistence write.
            marks++
        }
    }

    private object FixedVersion : AppVersionProvider {
        override val versionName = CURRENT_VERSION
        override val packageName = "me.manga.kira"
    }

    private object NoOpLauncher : IntentLauncher {
        override fun openUrl(url: String) = Unit

        override fun openPlayStorePage(packageName: String) = Unit

        override fun shareText(
            text: String,
            title: String,
        ) = Unit
    }

    companion object {
        const val CURRENT_VERSION = "1.2.3"
        const val SEEN_KEY = "whats_new_last_shown_version_name"
        private const val TRANSITION_MILLIS = 240
    }
}
