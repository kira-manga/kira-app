package me.manga.yamiapk.presentation.features.onboarding.theme_selection

import AutoSubtitleText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.ItemsGroup

@Composable
fun ThemeSelector(
    themes: List<AppTheme>,
    selected: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onRequestNotificationPermission: () -> Unit,

) {
    ItemsGroup(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4F)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            TabRow(
                selectedTabIndex = themes.indexOf(selected),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0F),
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[themes.indexOf(selected)]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                themes.forEach { theme ->
                    Tab(
                        selected = selected == theme,
                        onClick = {
                            onThemeSelected(theme)
                        },
                        icon = {
                            Icon(
                                imageVector = when (theme) {
                                    AppTheme.Light -> Icons.Default.LightMode
                                    AppTheme.Dark -> Icons.Default.DarkMode
                                    AppTheme.System -> Icons.Default.SettingsBrightness
                                },
                                contentDescription =  stringResource(id = theme.displayNameRes)
                            )
                        },
                        text = {
                            AutoSubtitleText(
                                text =  stringResource(id = theme.displayNameRes),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp
                                , color = MaterialTheme.colorScheme.primary)
                                , maxLines = 1
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            // Notification permission section
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                AutoSubtitleText(
                    text = stringResource(R.string.enable_notifications),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                AutoSubtitleText(
                    text = stringResource(R.string.notification_permission),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    maxLines = 3,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRequestNotificationPermission,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        AutoSubtitleText(
                            text = stringResource(R.string.grant_permission),
                            maxLines = 1,
                            minSize = 2.sp,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}
