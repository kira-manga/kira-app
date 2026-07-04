package me.manga.yamiapk.presentation.features.library_details.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaTopAppBar(
    title: String,
    onBackClick: () -> Unit,
    cancelAllDownloads: () -> Unit,
    isDownloadingAll: Boolean,
    backgroundColor: Color = MaterialTheme.colorScheme. background,
    onRefreshClick: () -> Unit,
    onFilterClick: () -> Unit,
    onDeleteDownloads: () -> Unit,
    ) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBarCom(
        title =title ,
        fontWeight = FontWeight.Normal,
        backgroundColor = backgroundColor,
        titleSize = 20.sp,
        navigationIcon = { IconButton(onBackClick)
        { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.desc_back)) } },
        actions = {

            if (isDownloadingAll) {
                IconButton(onClick = { cancelAllDownloads() }) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        tint = MaterialTheme.colorScheme.error,
                        contentDescription = stringResource(R.string.cancel)
                    )
                }
            }

            IconButton(
                onClick = { onFilterClick() },
            ) {
                Icon(Icons.Default.FilterList, stringResource(R.string.contentDescription_filter))
            }

            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.sort_options_title))
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {

                DropdownMenuItem(

                    text = { Text(stringResource(R.string.delete_all_downloaded_chapters)) },
                    onClick = {
                        menuExpanded = false
                        onDeleteDownloads()
                    }
                )
                DropdownMenuItem(

                    text = { Text(stringResource(R.string.dropdown_button_refresh)) },
                    onClick = {
                        menuExpanded = false
                        onRefreshClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_share)) },
                    onClick = {
                        menuExpanded = false
                    }
                )
            }
        }

    )
}