@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.intent.IntentLauncher
import me.manga.kira.presentation.sourceaccess.StartReadingViewModel
import me.manga.kira.ui.sourceaccess.StartReadingActions
import me.manga.kira.ui.sourceaccess.StartReadingScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Route host for onboarding and later locked-state activation entry points. */
@Composable
fun StartReadingScreenRoute(
    navController: NavController,
    onboarding: Boolean,
) {
    val viewModel: StartReadingViewModel = koinViewModel()
    val prefs: SharedPrefsHelper = koinInject()
    val launcher: IntentLauncher = koinInject()

    StartReadingScreen(
        viewModel = viewModel,
        actions =
            StartReadingActions(
                onActivationSucceeded = {
                    val destination = if (onboarding) Screen.Sources else Screen.RepoSettings()
                    navController.replaceCurrent(destination, clearOnboarding = onboarding)
                },
                onImport = {
                    navController.safeNavigate(Screen.BackupRework(completeStartFlowOnImport = true))
                },
                onContinueToLibrary = {
                    prefs.putBoolean(StorageKeys.FIRST_LAUNCH, false)
                    navController.openLibraryAsRoot()
                },
                onOpenUrl = launcher::openUrl,
                onBack = { navController.safePopBackStack() },
            ),
    )
}

private fun NavController.replaceCurrent(
    destination: Screen,
    clearOnboarding: Boolean,
) {
    val currentId = currentDestination?.id
    navigate(destination) {
        if (clearOnboarding) {
            popUpTo(graph.startDestinationId) { inclusive = true }
        } else if (currentId != null) {
            popUpTo(currentId) { inclusive = true }
        }
        launchSingleTop = true
    }
}

private fun NavController.openLibraryAsRoot() {
    navigate(Screen.Library) {
        popUpTo(graph.startDestinationId) { inclusive = true }
        launchSingleTop = true
    }
}
