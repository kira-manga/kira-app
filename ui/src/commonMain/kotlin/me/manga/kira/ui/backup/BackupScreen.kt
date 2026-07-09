package me.manga.kira.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.backup.BackupPhase
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.presentation.backup.BackupEffect
import me.manga.kira.presentation.backup.BackupIntent
import me.manga.kira.presentation.backup.BackupState
import me.manga.kira.presentation.backup.BackupViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backup_busy_cbz_hint
import me.manga.kira.ui.generated.resources.backup_error_generic
import me.manga.kira.ui.generated.resources.backup_error_invalid_file
import me.manga.kira.ui.generated.resources.backup_error_io
import me.manga.kira.ui.generated.resources.backup_error_newer_version
import me.manga.kira.ui.generated.resources.backup_error_too_large
import me.manga.kira.ui.generated.resources.backup_export_action
import me.manga.kira.ui.generated.resources.backup_export_done_title
import me.manga.kira.ui.generated.resources.backup_export_scoped_subtitle
import me.manga.kira.ui.generated.resources.backup_export_skipped_hint
import me.manga.kira.ui.generated.resources.backup_export_subtitle
import me.manga.kira.ui.generated.resources.backup_export_summary
import me.manga.kira.ui.generated.resources.backup_export_title
import me.manga.kira.ui.generated.resources.backup_failed_title
import me.manga.kira.ui.generated.resources.backup_import_action
import me.manga.kira.ui.generated.resources.backup_import_done_title
import me.manga.kira.ui.generated.resources.backup_import_subtitle
import me.manga.kira.ui.generated.resources.backup_import_summary_chapters
import me.manga.kira.ui.generated.resources.backup_import_summary_history
import me.manga.kira.ui.generated.resources.backup_import_summary_mangas
import me.manga.kira.ui.generated.resources.backup_import_title
import me.manga.kira.ui.generated.resources.backup_include_downloads
import me.manga.kira.ui.generated.resources.backup_include_downloads_hint
import me.manga.kira.ui.generated.resources.backup_ok
import me.manga.kira.ui.generated.resources.backup_progress_exporting
import me.manga.kira.ui.generated.resources.backup_progress_importing
import me.manga.kira.ui.generated.resources.backup_scoped_desc
import me.manga.kira.ui.generated.resources.backup_scoped_title
import me.manga.kira.ui.generated.resources.backup_stop
import me.manga.kira.ui.generated.resources.backup_stopped_message
import me.manga.kira.ui.generated.resources.backup_stopped_title
import me.manga.kira.ui.generated.resources.backup_title
import me.manga.kira.ui.generated.resources.desc_back
import me.manga.kira.ui.generated.resources.downloads
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Backup & restore. Full-library mode shows Export + Import; a scoped instance (opened from
 * Details or the Library multi-select) shows the selection summary and Export only. The platform
 * file pickers live in `:composeApp` — this screen only relays the picker effects out through
 * the two callbacks and feeds results back as intents.
 */
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit,
    onLaunchExportPicker: (archivePath: String, suggestedName: String) -> Unit,
    onLaunchImportPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    BackupScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateBack = onNavigateBack,
        onLaunchExportPicker = onLaunchExportPicker,
        onLaunchImportPicker = onLaunchImportPicker,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupScreenContent(
    state: BackupState,
    effects: Flow<BackupEffect>,
    onIntent: (BackupIntent) -> Unit,
    onNavigateBack: () -> Unit,
    onLaunchExportPicker: (String, String) -> Unit,
    onLaunchImportPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is BackupEffect.LaunchExportPicker ->
                    onLaunchExportPicker(effect.archivePath, effect.suggestedName)
                BackupEffect.LaunchImportPicker -> onLaunchImportPicker()
                BackupEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            stringResource(
                                if (state.isScoped) Res.string.backup_scoped_title else Res.string.backup_title,
                            ),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                navigationIcon = {
                    IconButton(onClick = { onIntent(BackupIntent.OnBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.desc_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val spacing = LocalSpacing.current
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
        ) {
            if (state.isScoped) {
                ScopedSummaryCard(titles = state.scopeTitles)
                Spacer(Modifier.height(spacing.lg))
            }

            BackupActionCard(
                icon = Icons.Outlined.FileUpload,
                title = stringResource(Res.string.backup_export_title),
                subtitle =
                    stringResource(
                        if (state.isScoped) {
                            Res.string.backup_export_scoped_subtitle
                        } else {
                            Res.string.backup_export_subtitle
                        },
                    ),
                actionLabel = stringResource(Res.string.backup_export_action),
                enabled = state.canStartRun,
                onAction = { onIntent(BackupIntent.OnExport) },
                extraContent = {
                    IncludeDownloadsToggle(
                        checked = state.includeDownloads,
                        enabled = state.canStartRun,
                        onToggle = { onIntent(BackupIntent.OnToggleIncludeDownloads) },
                    )
                },
            )

            if (!state.isScoped) {
                Spacer(Modifier.height(spacing.lg))
                BackupActionCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(Res.string.backup_import_title),
                    subtitle = stringResource(Res.string.backup_import_subtitle),
                    actionLabel = stringResource(Res.string.backup_import_action),
                    enabled = state.canStartRun,
                    onAction = { onIntent(BackupIntent.OnImport) },
                )
            }

            if (state.isCbzConversionRunning) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = stringResource(Res.string.backup_busy_cbz_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    BackupProgressDialog(state = state, onIntent = onIntent)
}

@Composable
private fun ScopedSummaryCard(titles: List<String>) {
    val spacing = LocalSpacing.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = stringResource(Res.string.backup_scoped_desc),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(spacing.sm))
            titles.forEach { title ->
                Text(
                    text = "• $title",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BackupActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extraContent != null) {
                Spacer(Modifier.height(spacing.sm))
                extraContent()
            }
            Spacer(Modifier.height(spacing.md))
            Button(
                onClick = onAction,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Phase B — pack downloaded chapters (their CBZs) into the archive. */
@Composable
private fun IncludeDownloadsToggle(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.backup_include_downloads),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(Res.string.backup_include_downloads_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
    }
}

/**
 * State-driven progress/terminal dialog — same posture as the Settings `CbzConversionDialog`:
 * dismissal is blocked while running; a terminal snapshot routes back-press/outside-tap to
 * [BackupIntent.OnDismissResult]. The domain `failed` flag is presence-only, so the failure copy
 * is built here from the typed [BackupState.error].
 */
@Composable
private fun BackupProgressDialog(
    state: BackupState,
    onIntent: (BackupIntent) -> Unit,
) {
    val progress = state.progress
    val hasFailure = progress.failed || state.error != null
    val visible =
        progress.isRunning ||
            hasFailure ||
            progress.wasStopped ||
            progress.exportResult != null ||
            progress.importResult != null
    if (!visible) return

    AlertDialog(
        onDismissRequest = { if (!progress.isRunning) onIntent(BackupIntent.OnDismissResult) },
        properties =
            DialogProperties(
                dismissOnBackPress = !progress.isRunning,
                dismissOnClickOutside = !progress.isRunning,
            ),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            when {
                progress.isRunning -> Unit
                hasFailure ->
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                progress.wasStopped ->
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                else ->
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
            }
        },
        title = {
            val title =
                when {
                    progress.isRunning ->
                        stringResource(
                            if (progress.phase == BackupPhase.IMPORTING) {
                                Res.string.backup_progress_importing
                            } else {
                                Res.string.backup_progress_exporting
                            },
                        )
                    hasFailure -> stringResource(Res.string.backup_failed_title)
                    progress.wasStopped -> stringResource(Res.string.backup_stopped_title)
                    progress.importResult != null -> stringResource(Res.string.backup_import_done_title)
                    else -> stringResource(Res.string.backup_export_done_title)
                }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color =
                    if (!progress.isRunning && hasFailure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        },
        text = {
            when {
                progress.isRunning -> BackupRunningBody(progress)
                hasFailure ->
                    Text(
                        text = backupErrorText(state.error),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                progress.wasStopped ->
                    Text(
                        text = stringResource(Res.string.backup_stopped_message),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                progress.importResult != null -> ImportDoneBody(progress.importResult!!)
                progress.exportResult != null -> ExportDoneBody(progress.exportResult!!)
                else -> Unit
            }
        },
        confirmButton = {
            if (progress.isRunning) {
                OutlinedButton(
                    onClick = { onIntent(BackupIntent.OnStop) },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(Res.string.backup_stop))
                }
            } else {
                Button(
                    onClick = { onIntent(BackupIntent.OnDismissResult) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.backup_ok))
                }
            }
        },
    )
}

@Composable
private fun BackupRunningBody(progress: BackupProgress) {
    val spacing = LocalSpacing.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (progress.currentTitle.isNotEmpty()) {
            Text(
                text = progress.currentTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(spacing.sm))
        }
        if (progress.totalMangas > 0) {
            LinearProgressIndicator(
                progress = { progress.processedMangas.toFloat() / progress.totalMangas.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "${progress.processedMangas} / ${progress.totalMangas}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.totalDownloads > 0) {
                Spacer(Modifier.height(spacing.sm))
                LinearProgressIndicator(
                    progress = { progress.processedDownloads.toFloat() / progress.totalDownloads.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text =
                        "${stringResource(Res.string.downloads)}: " +
                            "${progress.processedDownloads} / ${progress.totalDownloads}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ExportDoneBody(result: BackupExportResult) {
    val spacing = LocalSpacing.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(Res.string.backup_export_summary, result.mangaCount, result.chapterCount),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.downloadCount > 0) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "${stringResource(Res.string.downloads)}: ${result.downloadCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.skippedLooseDownloads > 0) {
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = stringResource(Res.string.backup_export_skipped_hint, result.skippedLooseDownloads),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportDoneBody(result: BackupImportResult) {
    val spacing = LocalSpacing.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text =
                stringResource(
                    Res.string.backup_import_summary_mangas,
                    result.mangasAdded,
                    result.mangasMerged,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text =
                stringResource(
                    Res.string.backup_import_summary_chapters,
                    result.chaptersAdded,
                    result.chaptersMerged,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(Res.string.backup_import_summary_history, result.historyMerged),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun backupErrorText(error: AppError?): String =
    when {
        error is AppError.Validation.OutOfRange && error.field == "formatVersion" ->
            stringResource(Res.string.backup_error_newer_version)
        error is AppError.Validation.OutOfRange && error.field == "backup_size" ->
            stringResource(Res.string.backup_error_too_large)
        error is AppError.Validation.Format ->
            stringResource(Res.string.backup_error_invalid_file)
        error is AppError.Storage.Io ->
            stringResource(Res.string.backup_error_io)
        else -> stringResource(Res.string.backup_error_generic)
    }
