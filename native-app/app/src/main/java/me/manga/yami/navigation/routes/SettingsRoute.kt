package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.presentation.features.settings.ui.screens.SettingsScreen

@Composable
fun SettingsRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
){


    SettingsScreen(navController)

}