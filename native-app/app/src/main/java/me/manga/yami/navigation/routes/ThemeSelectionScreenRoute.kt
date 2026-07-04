package me.manga.yamiapk.navigation.routes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.features.onboarding.theme_selection.AppTheme
import me.manga.yamiapk.presentation.features.onboarding.theme_selection.ThemeSelectionScreen
import me.manga.yamiapk.presentation.features.onboarding.viewmodel.OnboardingViewModel


@Composable
fun ThemeSelectionScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    onboardingViewModel : OnboardingViewModel = hiltViewModel()

    ){
    val isDarkMode by onboardingViewModel.darkMode.collectAsStateWithLifecycle()
    val isFollowSystem by onboardingViewModel.followSystem.collectAsStateWithLifecycle()
    val currentTheme = when {
        isFollowSystem        -> AppTheme.System
        isDarkMode            -> AppTheme.Dark
        else                  -> AppTheme.Light
    }

    ThemeSelectionScreen(
        currentTheme = currentTheme,
        onThemeSelected = {

            when (it) {
                AppTheme.Light -> {
                    onboardingViewModel.toggleFollowSystem(false)
                    onboardingViewModel.toggleDarkMode(false)
                }

                AppTheme.Dark -> {
                    onboardingViewModel.toggleFollowSystem(false)
                    onboardingViewModel.toggleDarkMode(true)

                }

                AppTheme.System -> onboardingViewModel.toggleFollowSystem(true)
            }
        }) {

        navController.navigate(Screen.Sources)


    }
    NotificationPermissionRequester{
    }
}
@Composable
fun NotificationPermissionRequester(onResult: (granted: Boolean) -> Unit = {}) {
    // 1) create the launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onResult(granted)
    }
    val context = LocalContext.current

    // 2) check & fire it exactly once
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!has) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onResult(true)
            }
        } else {
            // on older Androids it’s implicitly granted
            onResult(true)
        }
    }
}
