package me.manga.yamiapk.presentation.features.reader.ui.components.reading_mode_dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode

@Composable
fun ReadingModeDialog(
    currentMode: ReadingMode,
    onModeSelected: (ReadingMode) -> Unit,
    onDismissRequest: () -> Unit,
    onApply: () -> Unit
) {
    // local selection state
    var selected by remember { mutableStateOf(currentMode) }
    val scrollState = rememberScrollState()

    // Prepare chip labels
    val labels = ReadingMode.entries
    val selectedLabel = stringResource(selected.titleRes)

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            // cap at 90% width, 80% height
            modifier = Modifier
                .fillMaxWidth(1f),
            tonalElevation = 8.dp,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column {
                // Header
                Text(
                    text = "Reading mode",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )

                // Scrollable list container
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(vertical = 8.dp)
                ) {

// Chips selection
                    ReadingModeChips(
                        modes = labels,
                        selectedMode = selected,
                        onModeSelected = { newLabel ->


                           selected = newLabel
                        }
                    )


                    Divider()

                    // Footer actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                selected = currentMode
                                onDismissRequest()
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.but_revert),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,


                                )

                        }

                        Spacer(Modifier.width(12.dp))

                        Button(
                            onClick = {
                                onModeSelected(selected)
                                onApply()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground,
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Check,contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.background)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.but_apply),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.background
                            )
                        }
                    }

                }

            }
        }
    }
}

@Composable
fun ReadingModeSelector(
    modes: Array<ReadingMode>,
    selectedMode: ReadingMode,
    onModeSelected: (ReadingMode) -> Unit,
    modifier: Modifier = Modifier,
    buttonShape: Shape = MaterialTheme.shapes.medium,
    buttonPadding: PaddingValues = PaddingValues(vertical = 4.dp, horizontal = 8.dp)
) {
    Column(modifier = modifier) {
        modes.forEach { mode ->
            val isSelected = mode == selectedMode
            OutlinedButton(
                onClick = { onModeSelected(mode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(buttonPadding),
                shape = buttonShape,
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 2.dp,

                    ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = mode.iconRes),
                    contentDescription = mode.name,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
