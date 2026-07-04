package me.manga.yamiapk.core.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R

@Composable
fun ProgressState.Loading.formattedPercent(): String {
    return stringResource(
        id = R.string.progress_percent,
        percent
    )
}

@Composable
fun ProgressState.Loading.formattedSize(): String {
    return stringResource(
        id = R.string.progress_size,
        formatBytes(bytesRead),
        formatBytes(totalBytes)
    )
}

@Composable
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 ->
            stringResource(R.string.bytes_b, bytes)

        bytes < 1024 * 1024 ->
            stringResource(R.string.bytes_kb, bytes / 1024)

        else ->
            stringResource(R.string.bytes_mb, bytes / (1024 * 1024))
    }
}
