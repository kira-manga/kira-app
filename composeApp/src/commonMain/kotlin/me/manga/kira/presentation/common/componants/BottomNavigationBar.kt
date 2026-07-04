package me.manga.kira.presentation.common.componants

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.title_history
import me.manga.kira.composeapp.generated.resources.title_home
import me.manga.kira.composeapp.generated.resources.title_library
import me.manga.kira.composeapp.generated.resources.title_notifications
import me.manga.kira.composeapp.generated.resources.title_settings
import me.manga.kira.navigation.Screen
import me.manga.kira.ui.theme.KiraBrand
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Five-tab bottom navigation: Library / Updates / Home / History / Settings.
 *
 * Redesign 2026-06: a **floating capsule** (rounded, elevated, inset above the system nav bar /
 * home indicator). The active tab is a coral→amber gradient pill with icon + label; the other four
 * are icon-only in the muted tone. Same destinations, same tab-reselect / `popUpTo(Library)` /
 * save-restore navigation semantics as before — only the chrome changed from the flat M3
 * `NavigationBar` to this capsule.
 */
@Composable
fun BottomNavigationBar(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val items: List<Triple<Screen, ImageVector, StringResource>> = listOf(
        Triple(Screen.Library, Icons.AutoMirrored.Filled.LibraryBooks, Res.string.title_library),
        Triple(Screen.Updates, Icons.Default.Notifications, Res.string.title_notifications),
        Triple(Screen.Home, Icons.Default.Home, Res.string.title_home),
        Triple(Screen.History, Icons.Default.History, Res.string.title_history),
        Triple(Screen.Setting, Icons.Default.Settings, Res.string.title_settings),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().height(66.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                items.forEach { (screen, icon, labelRes) ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == screen.route } == true

                    val onClick: () -> Unit = {
                        // Re-tapping the selected tab is a launchSingleTop no-op. popUpTo(Library)
                        // keeps a flat single-entry-per-tab stack (Library is the tab root in every
                        // session — see git history for why findStartDestination() is wrong on first run).
                        navController.navigate(screen) {
                            popUpTo(Screen.Library) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    if (selected) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(KiraBrand.Gradient)
                                .clickable(onClick = onClick)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = stringResource(labelRes),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = onClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(labelRes),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
