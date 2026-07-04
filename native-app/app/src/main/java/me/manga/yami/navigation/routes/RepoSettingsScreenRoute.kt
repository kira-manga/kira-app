package me.manga.yamiapk.navigation.routes

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import me.manga.yamiapk.core.storage.PrefsDelegate
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.repo_settings.ui.screens.RepoSettingsScreen
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel

@Composable
fun RepoSettingsScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    repoSettingsViewModel: RepoSettingsViewModel

){
    val context = LocalContext.current

    val args = backStackEntry.toRoute<Screen.RepoSettings>()
    var firstLaunch by PrefsDelegate(
        context    = context,
        key        = "first_launch",
        defaultValue = true
    )
    RepoSettingsScreen(isFirstOpen = args.isFirstOpen, onFinish = {
        firstLaunch = false
        navController.navigate(Screen.Library){
      popUpTo(navController.graph.startDestinationId) {
        inclusive = true
      }
      // Avoid multiple copies
      launchSingleTop = true
        }
    },viewModel =repoSettingsViewModel){
        navController.safePopBackStack()
    }

}