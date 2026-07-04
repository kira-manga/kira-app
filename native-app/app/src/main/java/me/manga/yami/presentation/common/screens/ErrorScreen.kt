package me.manga.yamiapk.presentation.common.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.buttons.IconAboveTextButton

@Composable
fun ErrorScreen(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onHelp: () -> Unit,
    onBack: (() -> Unit)? = null      // optional back callback
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 1) Back arrow at top-start, if requested
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.desc_back)
                )
            }
        }

        // 2) Centered error message + actions
        Spacer(modifier = Modifier.weight( 1f))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconAboveTextButton(
                title = stringResource(R.string.retry),
                icon = Icons.Default.Refresh,
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            )
            IconAboveTextButton(
                title = stringResource(R.string.action_open_in_browser),
                icon = Icons.Default.WebAsset,
                onClick = onOpenInBrowser,
                modifier = Modifier.weight(1f)
            )
            IconAboveTextButton(
                title = stringResource(R.string.help),
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                onClick = onHelp,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.weight(if (onBack != null) 0.9f else 1f))
    }
}
