package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import me.manga.kira.core.result.AppResult

/**
 * Platform file-picker round-trips for the Backup & restore feature. Repositories and ViewModels
 * only ever see local paths — no Uri/NSURL crosses a module boundary. Shipping mobile pickers
 * acquire a bounded, owned app-cache snapshot; Desktop leaves cloning to the repository.
 */
interface BackupFilePicker {
    /**
     * Hand the finished export artifact at [sourcePath] (app cache; its file name is already the
     * suggested display name) to the platform save UI. [onResult] fires once: `true` when the
     * file was delivered to the user-chosen destination, `false` on cancel or copy failure.
     * The caller discards the cache artifact on every outcome.
     */
    fun launchExport(
        sourcePath: String,
        suggestedName: String,
        onResult: (delivered: Boolean) -> Unit,
    )

    /**
     * Open the platform file-picker for a backup archive. Success(null) is user cancellation;
     * acquisition failures are typed, not disguised as cancellation. A non-null success names
     * a bounded owned snapshot on mobile. Import claims it once; a busy/disposed caller must
     * discard only its unclaimed capability. No picker recursively clears a shared cache root.
     */
    fun launchImport(onResult: (AppResult<String?>) -> Unit)
}

/**
 * Composable factory — same posture as [rememberNotificationPermissionRequester]: the Android
 * actual needs `rememberLauncherForActivityResult` (Activity-scoped), so the factory itself must
 * be composable; iOS presents a `UIDocumentPickerViewController` off the root view controller;
 * Desktop is a best-effort AWT `FileDialog` (compile-parity target, not shipping).
 */
@Composable
expect fun rememberBackupFilePicker(): BackupFilePicker

/** Provenance string stamped into exported backups: "android" / "ios" / "desktop". */
expect fun backupPlatformName(): String
