package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.features.onboarding.welcome.WelcomeScreen

@Composable
fun WelcomeScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,


){

    WelcomeScreen {
        navController.navigate(Screen.Theme)
    }
}
