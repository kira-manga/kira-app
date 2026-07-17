@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.ui.sourceaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.Flow
import me.manga.kira.presentation.sourceaccess.StartReadingEffect
import me.manga.kira.presentation.sourceaccess.StartReadingIntent
import me.manga.kira.presentation.sourceaccess.StartReadingState
import me.manga.kira.presentation.sourceaccess.StartReadingViewModel
import me.manga.kira.ui.components.KIRA_GUIDE_URL
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.ui.components.WEBSITE_URL
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.start_reading_activate
import me.manga.kira.ui.generated.resources.start_reading_activation_discovery
import me.manga.kira.ui.generated.resources.start_reading_activation_help
import me.manga.kira.ui.generated.resources.start_reading_activation_label
import me.manga.kira.ui.generated.resources.start_reading_activation_placeholder
import me.manga.kira.ui.generated.resources.start_reading_continue
import me.manga.kira.ui.generated.resources.start_reading_guide
import me.manga.kira.ui.generated.resources.start_reading_import
import me.manga.kira.ui.generated.resources.start_reading_intro
import me.manga.kira.ui.generated.resources.start_reading_invalid_link
import me.manga.kira.ui.generated.resources.start_reading_social
import me.manga.kira.ui.generated.resources.start_reading_title
import me.manga.kira.ui.generated.resources.start_reading_website
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Start Reading onboarding and activation surface. */
@Composable
fun StartReadingScreen(
    viewModel: StartReadingViewModel,
    actions: StartReadingActions,
) {
    val state by viewModel.state.collectAsState()
    StartReadingScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        actions = actions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartReadingScreenContent(
    state: StartReadingState,
    effects: Flow<StartReadingEffect>,
    onIntent: (StartReadingIntent) -> Unit,
    actions: StartReadingActions,
) {
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                StartReadingEffect.ActivationSucceeded -> actions.onActivationSucceeded()
                StartReadingEffect.OpenImport -> actions.onImport()
                StartReadingEffect.ContinueToLibrary -> actions.onContinueToLibrary()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(Res.string.start_reading_title)) },
                navigationIcon = {
                    if (actions.onBack != null) {
                        KiraIconButton(
                            icon = KiraIcons.Back,
                            contentDescription = stringResource(Res.string.back),
                            onClick = actions.onBack,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        StartReadingBody(
            state = state,
            onIntent = onIntent,
            actions = actions,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun StartReadingBody(
    state: StartReadingState,
    onIntent: (StartReadingIntent) -> Unit,
    actions: StartReadingActions,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoStories,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.start_reading_intro),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReadingGuideLink(onOpenUrl = actions.onOpenUrl)
        ActivationCard(state = state, onIntent = onIntent)
        OutlinedButton(
            onClick = { onIntent(StartReadingIntent.OnImport) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Outlined.FileUpload, contentDescription = null)
            Text(
                text = stringResource(Res.string.start_reading_import),
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        TextButton(
            onClick = { onIntent(StartReadingIntent.OnContinueToLibrary) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.start_reading_continue))
        }
        HorizontalDivider()
        Text(
            text = stringResource(Res.string.start_reading_activation_discovery),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WebsiteLink(onOpenUrl = actions.onOpenUrl)
        Text(
            text = stringResource(Res.string.start_reading_social),
            style = MaterialTheme.typography.titleSmall,
        )
        KiraSocialMediaRow(onOpenUrl = actions.onOpenUrl)
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

@Composable
private fun ReadingGuideLink(onOpenUrl: (String) -> Unit) {
    OutlinedButton(
        onClick = { onOpenUrl(KIRA_GUIDE_URL) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.start_reading_guide))
    }
}

@Composable
private fun WebsiteLink(onOpenUrl: (String) -> Unit) {
    OutlinedButton(
        onClick = { onOpenUrl(WEBSITE_URL) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(imageVector = Icons.Outlined.Public, contentDescription = null)
        Text(stringResource(Res.string.start_reading_website))
    }
}

@Composable
private fun ActivationCard(
    state: StartReadingState,
    onIntent: (StartReadingIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            OutlinedTextField(
                value = state.activationLink,
                onValueChange = { onIntent(StartReadingIntent.OnActivationLinkChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.start_reading_activation_label)) },
                placeholder = { Text(stringResource(Res.string.start_reading_activation_placeholder)) },
                isError = state.invalidLink,
                supportingText = {
                    Text(
                        stringResource(
                            if (state.invalidLink) {
                                Res.string.start_reading_invalid_link
                            } else {
                                Res.string.start_reading_activation_help
                            },
                        ),
                    )
                },
                singleLine = true,
            )
            Button(
                onClick = { onIntent(StartReadingIntent.OnActivate) },
                enabled = !state.isActivating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.start_reading_activate))
            }
        }
    }
}
