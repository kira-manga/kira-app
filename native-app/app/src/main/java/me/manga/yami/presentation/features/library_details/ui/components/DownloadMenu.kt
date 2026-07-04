package me.manga.yamiapk.presentation.features.library_details.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity


@Composable
 fun DownloadMenu(
    expanded: Boolean,
    manga: SavedMangaEntity,
    onDismiss: () -> Unit,
    chapters: List<SavedChapterEntity>,
    showChaptersCheckBox: MutableState<Boolean>,
    selectedChapters: Set<SavedChapterEntity>,
    downloadAll: () -> Unit,
    onCustomDownload: (Set<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onSelectedChaptersChange: (Set<SavedChapterEntity>) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_download_all)) },
            onClick = {
                onDismiss()
                downloadAll()
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        showChaptersCheckBox.value -> if (selectedChapters.isEmpty()) stringResource(R.string.cancel_selection) else stringResource(R.string.download_selected_format, selectedChapters.size)
                        else -> stringResource(R.string.custom_download)
                    }
                )
            },
            onClick = {
                onDismiss()
                onCustomDownload(selectedChapters,manga)
                onSelectedChaptersChange(emptySet())
                showChaptersCheckBox.value = !showChaptersCheckBox.value
            }
        )
    }
}
