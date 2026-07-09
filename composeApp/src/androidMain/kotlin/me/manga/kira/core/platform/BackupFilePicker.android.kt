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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()

    val pendingExport = remember { mutableStateOf<PendingExport?>(null) }
    val pendingImport = remember { mutableStateOf<((String?) -> Unit)?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            val pending = pendingExport.value ?: return@rememberLauncherForActivityResult
            pendingExport.value = null
            if (uri == null) {
                pending.onResult(false)
            } else {
                scope.launch {
                    val delivered =
                        withContext(Dispatchers.IO) {
                            copyFileToUri(context, pending.sourcePath, uri)
                        }
                    pending.onResult(delivered)
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val callback = pendingImport.value ?: return@rememberLauncherForActivityResult
            pendingImport.value = null
            if (uri == null) {
                callback(null)
            } else {
                scope.launch {
                    val localPath = withContext(Dispatchers.IO) { copyUriToImportCache(context, uri) }
                    callback(localPath)
                }
            }
        }

    return remember(context, exportLauncher, importLauncher) {
        object : BackupFilePicker {
            override fun launchExport(
                sourcePath: String,
                suggestedName: String,
                onResult: (Boolean) -> Unit,
            ) {
                pendingExport.value = PendingExport(sourcePath, onResult)
                exportLauncher.launch(suggestedName)
            }

            override fun launchImport(onResult: (String?) -> Unit) {
                pendingImport.value = onResult
                // Backup archives are plain ZIPs; octet-stream covers providers that don't map
                // the .zip extension to a MIME type.
                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
    }
}

actual fun backupPlatformName(): String = "android"

private class PendingExport(
    val sourcePath: String,
    val onResult: (Boolean) -> Unit,
)

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

/** Stale copies from a previous import are garbage — the staging dir is recreated per pick. */
private fun copyUriToImportCache(
    context: Context,
    uri: Uri,
): String? =
    try {
        val dir =
            File(context.cacheDir, "backup_import").apply {
                deleteRecursively()
                mkdirs()
            }
        val target = File(dir, "import.kira.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
            target.absolutePath
        }
    } catch (ignored: Exception) {
        null
    }
