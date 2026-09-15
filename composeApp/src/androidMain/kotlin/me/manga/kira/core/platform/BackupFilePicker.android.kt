package me.manga.kira.core.platform

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.backup.BackupImportStaging
import org.koin.compose.koinInject
import java.io.File

/**
 * Android actual: SAF pickers via `rememberLauncherForActivityResult` (CreateDocument for save,
 * OpenDocument for open), with the stream copies hopped to [Dispatchers.IO] so a large archive
 * never blocks the main thread. Pending-callback holder pattern per
 * [rememberNotificationPermissionRequester]'s Android actual.
 */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker {
    val context = LocalContext.current.applicationContext
    val staging: BackupImportStaging = koinInject()
    val session = rememberBackupImportPickerSession()
    val exportAction = rememberBackupExportAction(context)
    val importAction = rememberBackupImportAction(context, staging, session)
    return remember(exportAction, importAction) { AndroidBackupFilePicker(exportAction, importAction) }
}

@Composable
private fun rememberBackupExportAction(context: Context): (PendingExport, String) -> Unit {
    val scope = rememberCoroutineScope()
    val pendingExport = remember { mutableStateOf<PendingExport?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            val pending = pendingExport.value ?: return@rememberLauncherForActivityResult
            pendingExport.value = null
            finishExport(context, scope, pending, uri)
        }
    return remember(exportLauncher) {
        { pending, suggestedName ->
            pendingExport.value = pending
            exportLauncher.launch(suggestedName)
        }
    }
}

@Composable
private fun rememberBackupImportAction(
    context: Context,
    staging: BackupImportStaging,
    session: BackupImportPickerSession,
): ((AppResult<String?>) -> Unit) -> Unit {
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) session.cancelPick()
            else session.acquire { checkpoint -> acquireAndroidBackup(context, uri, staging, checkpoint) }
        }
    return remember(importLauncher, session) {
        { onResult ->
            if (session.begin(onResult)) {
                try {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                } catch (failure: Exception) {
                    session.failPick(failure)
                }
            }
        }
    }
}

private class AndroidBackupFilePicker(
    private val exportAction: (PendingExport, String) -> Unit,
    private val importAction: ((AppResult<String?>) -> Unit) -> Unit,
) : BackupFilePicker {
    override fun launchExport(sourcePath: String, suggestedName: String, onResult: (Boolean) -> Unit) =
        exportAction(PendingExport(sourcePath, onResult), suggestedName)

    override fun launchImport(onResult: (AppResult<String?>) -> Unit) = importAction(onResult)
}

actual fun backupPlatformName(): String = "android"

private class PendingExport(
    val sourcePath: String,
    val onResult: (Boolean) -> Unit,
)

private fun finishExport(context: Context, scope: CoroutineScope, pending: PendingExport, uri: Uri?) {
    if (uri == null) {
        pending.onResult(false)
    } else {
        scope.launch {
            val delivered = withContext(Dispatchers.IO) { copyFileToUri(context, pending.sourcePath, uri) }
            pending.onResult(delivered)
        }
    }
}

private fun copyFileToUri(
    context: Context,
    sourcePath: String,
    uri: Uri,
): Boolean =
    try {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            File(sourcePath).inputStream().use { it.copyTo(out) }
            true
        } ?: false
    } catch (ignored: Exception) {
        false
    }
