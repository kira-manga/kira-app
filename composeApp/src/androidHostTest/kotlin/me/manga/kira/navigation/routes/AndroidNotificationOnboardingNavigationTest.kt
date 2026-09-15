package me.manga.kira.navigation.routes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.continue_string
import me.manga.kira.composeapp.generated.resources.grant_permission
import me.manga.kira.core.platform.NotificationPermissionRequesterFixture
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.core.webview.AndroidWebViewComposeTest
import me.manga.kira.data.repository.SourceAccessRepositoryImpl
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.domain.repository.ThemeRepository
import me.manga.kira.domain.usecase.sourceaccess.ActivateSourceAccessUseCase
import me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase
import me.manga.kira.domain.usecase.theme.ObservePureBlackUseCase
import me.manga.kira.domain.usecase.theme.SetAppThemeUseCase
import me.manga.kira.domain.usecase.theme.SetPureBlackUseCase
import me.manga.kira.navigation.Screen
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.platform.storage.AndroidSettingsFactory
import me.manga.kira.platform.toast.ToastShower
import me.manga.kira.presentation.sourceaccess.StartReadingViewModel
import me.manga.kira.presentation.theme.ThemeViewModel
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real route/effect/prefs proof; the fixed test graph is not the private AppNavHost root. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidNotificationOnboardingNavigationTest : AndroidWebViewComposeTest() {
    @Test
    fun optionalPermissionRouteCompletesFirstLaunchForSkipAndDenial() {
        listOf(false, true).forEach { explicitDenial -> completeCase(explicitDenial) }
    }

    private fun completeCase(explicitDenial: Boolean) {
        val storeName = "app10_onboarding_test_$explicitDenial"
        val settings = AndroidSettingsFactory(activity.application).createObservable(storeName)
        settings.clear()
        val fixture = OnboardingRouteFixture(settings)
        try {
            try {
                compose.runOnUiThread {
                    shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
                }
                show { fixture.Content() }
                completeThroughRealControls(fixture, explicitDenial)
            } finally {
                try {
                    destroyHost()
                } finally {
                    compose.runOnUiThread { fixture.close() }
                }
            }

            createHost()
            assertFreshHostPrefs(storeName)
        } finally {
            settings.clear()
        }
    }

    private fun assertFreshHostPrefs(storeName: String) {
        compose.runOnUiThread {
            // Fresh Android-backed facade after host replacement, not a recreated App-root assertion.
            val freshPrefs =
                SharedPrefsHelper(AndroidSettingsFactory(activity.application).createObservable(storeName))
            assertFalse(freshPrefs.getBoolean(StorageKeys.FIRST_LAUNCH, true))
            assertFalse(freshPrefs.getBoolean(StorageKeys.NOTIF_PERMISSION_ASKED, false))
            assertPermissionDenied()
        }
    }

    private fun completeThroughRealControls(
        fixture: OnboardingRouteFixture,
        explicitDenial: Boolean,
    ) {
        compose.runOnIdle {
            val entry = assertNotNull(fixture.nav.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<Screen.Theme>())
            assertEquals(Lifecycle.State.RESUMED, entry.lifecycle.currentState)
            assertTrue(fixture.prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true))
            assertTrue(fixture.permission.launches.isEmpty())
            assertTrue(fixture.toasts.isEmpty())
            assertPermissionDenied()
        }
        if (explicitDenial) denyFromGrantControl(fixture)
        compose.onNodeWithText(label(Res.string.continue_string)).assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = assertNotNull(fixture.nav.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<Screen.StartReading>())
            assertTrue(entry.toRoute<Screen.StartReading>().onboarding)
            assertTrue(fixture.prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true))
            assertPermissionDenied()
        }
        // ui.Res is internal; the en fixture uses strings_pfix_source_access.xml's actual label.
        val continueToLibrary = compose.onNodeWithText("Continue to Library")
        continueToLibrary.performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertLibraryCompletion(fixture, explicitDenial)
    }

    private fun assertLibraryCompletion(
        fixture: OnboardingRouteFixture,
        explicitDenial: Boolean,
    ) {
        compose.runOnIdle {
            assertTrue(assertNotNull(fixture.nav.currentDestination).hasRoute<Screen.Library>())
            assertNull(fixture.nav.previousBackStackEntry)
            assertFalse(fixture.prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true))
            assertFalse(fixture.prefs.getBoolean(StorageKeys.NOTIF_PERMISSION_ASKED, false))
            assertEquals(if (explicitDenial) 1 else 0, fixture.permission.launches.size)
            assertTrue(fixture.toasts.isEmpty())
            assertPermissionDenied()
        }
    }

    private fun denyFromGrantControl(fixture: OnboardingRouteFixture) {
        compose.onNodeWithText(label(Res.string.grant_permission)).performClick()
        compose.runOnIdle {
            val launch = fixture.permission.launches.single()
            assertEquals(ActivityResultContracts.RequestPermission::class.java, launch.contractType)
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, launch.input)
            assertTrue(fixture.permission.complete(granted = false))
            assertTrue(fixture.toasts.isEmpty())
        }
        compose.waitForIdle()
    }

    private fun assertPermissionDenied() {
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(activity.application, Manifest.permission.POST_NOTIFICATIONS),
        )
    }
}

private class OnboardingRouteFixture(
    settings: ObservableSettings,
) {
    val prefs = SharedPrefsHelper(settings)
    val permission = NotificationPermissionRequesterFixture()
    val toasts = mutableListOf<String>()
    lateinit var nav: NavHostController

    private val app =
        koinApplication {
            allowOverride(false)
            modules(
                module {
                    single { prefs }
                    single<ToastShower> {
                        object : ToastShower {
                            override fun showShort(message: String) {
                                toasts += message
                            }

                            override fun showLong(message: String) {
                                toasts += message
                            }
                        }
                    }
                    single<IntentLauncher> { NoExternalIntents }
                    viewModel {
                        ThemeViewModel(
                            ObserveAppThemeUseCase(FixedThemeRepository),
                            ObservePureBlackUseCase(FixedThemeRepository),
                            SetAppThemeUseCase(FixedThemeRepository),
                            SetPureBlackUseCase(FixedThemeRepository),
                        )
                    }
                    viewModel {
                        StartReadingViewModel(ActivateSourceAccessUseCase(SourceAccessRepositoryImpl(settings)))
                    }
                },
            )
        }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        val controller = rememberNavController()
        SideEffect { nav = controller }
        KoinIsolatedContext(app) {
            // The real Theme route creates the requester. Do not mount permission.Content() here.
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permission) {
                NavHost(
                    navController = controller,
                    startDestination = Screen.Theme,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable<Screen.Theme> { ThemeSelectionScreenRoute(controller, it) }
                    composable<Screen.StartReading> {
                        StartReadingScreenRoute(controller, onboarding = it.toRoute<Screen.StartReading>().onboarding)
                    }
                    // Destination sentinel only: neither the real Library UI nor App-root selection.
                    composable<Screen.Library> { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }

    fun close() = app.close()

    private object FixedThemeRepository : ThemeRepository {
        override fun observeAppTheme(): Flow<AppTheme> = flowOf(AppTheme.Light)

        override fun observePureBlack(): Flow<Boolean> = flowOf(false)

        override suspend fun setAppTheme(theme: AppTheme) = error("Theme mutation is outside this route test")

        override suspend fun setPureBlack(enabled: Boolean) = error("Theme mutation is outside this route test")
    }

    private object NoExternalIntents : IntentLauncher {
        override fun openUrl(url: String) = error("No external intent expected")

        override fun openPlayStorePage(packageName: String) = error("No external intent expected")

        override fun shareText(
            text: String,
            title: String,
        ) = error("No external intent expected")
    }
}
