package me.manga.yamiapk.presentation.features.home.ui.screens.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.theme.YamiMangaTheme

@Composable
fun SiteMaintenanceScreen(
    siteName: String,
    modifier: Modifier = Modifier
) {
    SimpleStatusScreen(
        icon = Icons.Default.Build,
        iconColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
        title = stringResource(R.string.under_maintenance),
        subtitle = siteName,
        message = stringResource(R.string.this_site_is_currently_under_maintenance_please_check_back_later_or_try_a_different_source),
        modifier = modifier
    )
}

@Composable
fun SiteStoppedScreen(
    siteName: String,
    modifier: Modifier = Modifier
) {
    SimpleStatusScreen(
        icon = Icons.Default.Error,
        iconColor = MaterialTheme.colorScheme.error,
        backgroundColor = MaterialTheme.colorScheme.errorContainer,
        title = stringResource(R.string.site_stopped),
        subtitle = siteName,
        message = stringResource(R.string.this_site_has_been_stopped_and_is_no_longer_available_please_select_a_different_source_from_the_tabs_above),
        modifier = modifier
    )
}

@Composable
private fun SimpleStatusScreen(
    icon: ImageVector,
    iconColor: Color,
    backgroundColor: Color,
    title: String,
    subtitle: String,
    message: String,
    modifier: Modifier = Modifier
) {
    // Use fillParentMaxSize to take full available space in LazyColumn
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Icon
            Card(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(60.dp),
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Site Name
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description Message
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}


@Composable
fun SiteAdultContentBlockedScreen(
    siteName: String,
    modifier: Modifier = Modifier
) {
    SimpleStatusScreen(
        icon = Icons.Default.Error, // تقدر تغيّرها لأي Icon عندك
        iconColor = MaterialTheme.colorScheme.tertiary,
        backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
        title = stringResource(R.string.adult_content_blocked_title),
        subtitle = siteName,
        message = stringResource(R.string.adult_content_blocked_message),
        modifier = modifier
    )
}
@Preview(showBackground = true)
@Composable
fun PreviewSiteMaintenanceScreen() {
    YamiMangaTheme(true) {
        SiteMaintenanceScreen(
            siteName = "MangaSite"
        )
    }
}
@Preview(showBackground = true)
@Composable
fun PreviewSiteAdultContentBlockedScreen() {
    YamiMangaTheme(true) {
        SiteAdultContentBlockedScreen(
            siteName = "MangaSite"
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewSiteStoppedScreen() {
    YamiMangaTheme(true) {
        SiteStoppedScreen(
            siteName = "MangaSite"
        )
    }
}