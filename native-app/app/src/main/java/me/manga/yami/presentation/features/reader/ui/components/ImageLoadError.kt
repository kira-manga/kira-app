package me.manga.yamiapk.presentation.features.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.reader.ui.screens.BorderedPrimaryButton

@Composable
fun ImageLoadError(
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.failed_to_load_image),
    onRetry: () -> Unit,
    onOpenInWebView: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BorderedPrimaryButton(
                    title = stringResource(R.string.retry),
                    onRetry = onRetry
                )
                BorderedPrimaryButton(
                    title =stringResource(R.string.action_open_in_browser),
                    onRetry = onOpenInWebView
                )
            }
        }
    }
}
