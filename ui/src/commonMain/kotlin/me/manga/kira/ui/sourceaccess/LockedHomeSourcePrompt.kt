@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.ui.sourceaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.home_edit_sources
import me.manga.kira.ui.generated.resources.home_no_sources_activated_body
import me.manga.kira.ui.generated.resources.home_no_sources_activated_title
import me.manga.kira.ui.generated.resources.home_no_sources_locked_body
import me.manga.kira.ui.generated.resources.home_no_sources_locked_title
import me.manga.kira.ui.generated.resources.start_reading_title
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Home replacement shown only for the typed no-enabled-source error while access is locked. */
@Composable
fun LockedHomeSourcePrompt(
    onStartReading: () -> Unit,
    startReadingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    HomeSourcePrompt(
        title = stringResource(Res.string.home_no_sources_locked_title),
        body = stringResource(Res.string.home_no_sources_locked_body),
        actionLabel = stringResource(Res.string.start_reading_title),
        onAction = onStartReading,
        actionEnabled = startReadingEnabled,
        modifier = modifier,
    )
}

/** Home replacement shown when source access is active but the user has enabled no sources. */
@Composable
fun ActivatedHomeSourcePrompt(
    onEditSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeSourcePrompt(
        title = stringResource(Res.string.home_no_sources_activated_title),
        body = stringResource(Res.string.home_no_sources_activated_body),
        actionLabel = stringResource(Res.string.home_edit_sources),
        onAction = onEditSources,
        modifier = modifier,
    )
}

@Composable
private fun HomeSourcePrompt(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    start = spacing.lg,
                    end = spacing.lg,
                    bottom = LocalBottomBarPadding.current,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoStories,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
