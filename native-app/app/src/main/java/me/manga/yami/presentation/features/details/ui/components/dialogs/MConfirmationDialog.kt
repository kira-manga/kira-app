package me.manga.yamiapk.presentation.features.details.ui.components.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter


@Composable
fun MConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    images: List<Int>,
    showContinue:Boolean

) {


    // 2. Pick one random ID when this dialog is first composed
    val randomImageId = remember { images.random() }

    AlertDialog(
        onDismissRequest = {
            // Called when the user taps outside the dialog or presses the back button
            onDismiss()
        },

        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = randomImageId
                    ),
                    contentDescription = "18+ Warning",
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                    , // very light red background
                    contentScale = ContentScale.FillBounds
                )

            }
        },
        confirmButton = {
            if (showContinue){
                TextButton(
                    onClick = {
                        onConfirm()
                    }
                ) {
                    Text(
                        text = "Continue",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7F)
                    )
                }}
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(
                    text = "Close",
                    fontWeight = FontWeight.Normal,
                    color = Color.Gray
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}