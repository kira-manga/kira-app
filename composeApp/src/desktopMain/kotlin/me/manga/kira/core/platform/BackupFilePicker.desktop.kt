package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop actual: best-effort AWT [FileDialog] (compile-parity target, not shipping). */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker = remember { DesktopBackupFilePicker() }

actual fun backupPlatformName(): String = "desktop"

private class DesktopBackupFilePicker : BackupFilePicker {

    override fun launchExport(
        sourcePath: String,
        suggestedName: String,
        onResult: (Boolean) -> Unit,
    ) {
        val dialog = FileDialog(null as Frame?, "Save backup", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) {
            onResult(false)
            return
        }
        val delivered = runCatching {
            File(sourcePath).copyTo(File(dir, name), overwrite = true)
            true
        }.getOrDefault(false)
        onResult(delivered)
    }

    override fun launchImport(onResult: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Open backup", FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        onResult(if (dir != null && name != null) File(dir, name).absolutePath else null)
    }
}
