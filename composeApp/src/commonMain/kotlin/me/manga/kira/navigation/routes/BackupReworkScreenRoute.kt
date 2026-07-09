package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.manga.kira.core.platform.rememberBackupFilePicker
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.backup.BackupIntent
import me.manga.kira.presentation.backup.BackupViewModel
import me.manga.kira.ui.backup.BackupScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Wire identity of one scoped-export selection entry. The whole selection travels as ONE
 * JSON-encoded route string ([Screen.BackupRework.scopeJson]) — a `List<String>` nav arg would
 * need a custom NavType and a separator-safe encoding for titles; JSON-in-one-string avoids both.
 */
@Serializable
data class BackupScopeKey(val api: String, val language: String, val title: String)

private val backupScopeJson = Json { ignoreUnknownKeys = true }

/** Encode a Details/Library selection into the [Screen.BackupRework] route argument. */
fun encodeBackupScope(keys: List<BackupScopeKey>): String =
    if (keys.isEmpty()) "" else backupScopeJson.encodeToString(keys)

/** Blank/undecodable/empty scope falls back to a full-library backup screen. */
internal fun decodeBackupScope(raw: String): BackupScope {
    if (raw.isBlank()) return BackupScope.FullLibrary
    val keys = runCatching {
        backupScopeJson.decodeFromString<List<BackupScopeKey>>(raw)
    }.getOrNull()
    if (keys.isNullOrEmpty()) return BackupScope.FullLibrary
    return BackupScope.Mangas(
        keys.map { MangaKey(api = it.api, language = it.language, title = it.title) },
    )
}

/**
 * Route host for the Backup & restore screen. Owns the [BackupViewModel] (scope passed as a Koin
 * parameter) and the platform file picker; the picker results feed back into the VM as intents,
 * closing the export/import round-trips.
 */
@Composable
fun BackupReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val args = backStackEntry.toRoute<Screen.BackupRework>()
    val scope = remember(args.scopeJson) { decodeBackupScope(args.scopeJson) }
    val viewModel: BackupViewModel = koinViewModel { parametersOf(scope) }
    val picker = rememberBackupFilePicker()
    BackupScreen(
        viewModel = viewModel,
        onNavigateBack = { navController.safePopBackStack() },
        onLaunchExportPicker = { archivePath, suggestedName ->
            picker.launchExport(archivePath, suggestedName) { delivered ->
                viewModel.submit(BackupIntent.OnExportDelivered(delivered))
            }
        },
        onLaunchImportPicker = {
            picker.launchImport { localPath ->
                viewModel.submit(BackupIntent.OnImportFilePicked(localPath))
            }
        },
    )
}
