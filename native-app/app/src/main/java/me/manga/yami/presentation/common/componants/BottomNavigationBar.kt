package me.manga.yamiapk.presentation.common.componants

import NavigationBarAutoText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.manga.yamiapk.R
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.double_click.NavigationHandlerHolder
import me.manga.yamiapk.theme.YamiMangaTheme

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val items = listOf(
        Triple(Screen.Library, Icons.AutoMirrored.Filled.LibraryBooks, R.string.title_library),
        Triple(Screen.Updates, Icons.Default.Notifications, R.string.title_notifications),
        Triple(Screen.Home, Icons.Default.Home, R.string.title_home),

        Triple(Screen.History, Icons.Default.History, R.string.title_history),
        Triple(Screen.Setting, Icons.Default.Settings, R.string.title_settings)
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
    ) {

        items.forEach { (screen, icon, labelRes) ->
            val selected = currentDestination
                ?.hierarchy
                ?.any {
                    it.route == screen.route } == true



            NavigationBarItem(
                icon = { Icon(icon, contentDescription = null) },
                label = { NavigationBarAutoText(stringResource(labelRes),  ) },
                selected = selected,
                onClick = {
                    if (selected && screen is Screen.Home) {
                        // User is already on Home and clicked again
                        NavigationHandlerHolder.homeReselectHandler?.onHomeTabReselected()
                    } else {
                        navController.navigate(screen) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                colors =    NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )

            )
        }
    }
}
@Preview(showBackground = true)
@Composable
fun BottomNavigationBarPreview() {
    // You can wrap in your app’s theme so the colors match exactly.
    YamiMangaTheme(true) {
        // rememberNavController() works fine for preview; nothing will actually navigate.
        BottomNavigationBar(navController = rememberNavController())
    }
}

