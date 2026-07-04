package me.manga.yamiapk.presentation.features.details.ui.components.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R

@Composable
fun AdultConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    header :String = stringResource(R.string.adult_filter_removal_header),
    title :String = stringResource(R.string.adult_filter_removal_title)
) {
    AlertDialog(
        onDismissRequest = {
            // Called when the user taps outside the dialog or presses the back button
            onDismiss()
        },
        title = {
            Text(
                text = header,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red.copy(0.8F)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // “18+” icon from Material Icons
                Icon(
                    painter = painterResource(R.drawable.ic_pluss18),
                    contentDescription = "+18 Icon",
                    modifier = Modifier
                        .size(120.dp)
                        .padding(8.dp),
                    tint = Color.Red.copy(0.65F)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(
                    text =stringResource(R.string.close),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Red
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7F)
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}
