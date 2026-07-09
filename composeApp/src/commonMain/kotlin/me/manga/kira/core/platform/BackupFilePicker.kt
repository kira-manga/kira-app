package me.manga.kira.core.platform

import androidx.compose.runtime.Composable

/**
 * Platform file-picker round-trips for the Backup & restore feature. Repositories and ViewModels
 * only ever see absolute paths inside the app sandbox — no Uri/NSURL crosses a module boundary.
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
     * Open the platform file-picker for a backup archive. [onResult] fires once with an
     * app-sandbox copy of the picked file, or `null` on cancel/failure. The copy lives in
     * ephemeral cache space that each platform actual reclaims (cleared on the next launch or
     * by the OS) — callers need no cleanup of their own.
     */
    fun launchImport(onResult: (localCachePath: String?) -> Unit)
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
