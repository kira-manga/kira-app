package me.manga.yamiapk.navigation.routes

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.core.storage.PrefsDelegate
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.features.onboarding.sources.SourcesScreen
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel

@Composable
fun SourcesScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    repoSettingsViewModel: RepoSettingsViewModel

){
    val context = LocalContext.current
    var firstLaunch by PrefsDelegate(
        context    = context,
        key        = "first_launch",
        defaultValue = true
    )

    SourcesScreen(repoSettingsViewModel){
        firstLaunch = false
        navController.navigate(Screen.Library){
            popUpTo(navController.graph.startDestinationId) {
                inclusive = true
            }
            // Avoid multiple copies
            launchSingleTop = true
        }
    }
}