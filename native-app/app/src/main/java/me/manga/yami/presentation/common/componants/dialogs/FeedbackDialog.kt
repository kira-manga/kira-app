package me.manga.yamiapk.presentation.common.componants.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.about.common.SocialMediaRow
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDialog(
    visible: Boolean,
    headerText: String,
    textFieldText: String,
    selectedType: ComplaintType? = null,
    onSubmit: (selectedType: ComplaintType?, feedbackBody: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var selectedTypeState by remember { mutableStateOf<ComplaintType?>(selectedType) }
    var feedbackBody by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val submitEnabled = remember(selectedTypeState, feedbackBody) {
        selectedTypeState != null && feedbackBody.length >= 5
    }

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        title = {
            Column {
                Text(
                    text = headerText,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.we_d_love_to_hear_from_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Category Dropdown Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.category),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedTypeState?.getDisplayName(context) ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.select_a_category)) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Dropdown"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            ComplaintType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = type.getDisplayName(context),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    onClick = {
                                        selectedTypeState = type
                                        expanded = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }

                // Feedback Input Section
                Column(
//                    Modifier.simpleVerticalScrollbar(scrollState = scrollState,),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.your_feedback),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = feedbackBody,
                        onValueChange = { feedbackBody = it },
                        label = { Text(text = textFieldText, fontSize = 14.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        minLines = 4,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        isError = feedbackBody.isNotEmpty() && feedbackBody.length < 5,
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (feedbackBody.length < 5) {
                                    Text(
                                        text = stringResource(R.string.minimum_5_characters_required),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }
                                Text(
                                    text = "${feedbackBody.length}/500",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }

                // Social Media Section
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.connect_with_us_in_social_media),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.you_ll_receive_a_prompt_response_our_developer_will_reach_out_to_you_shortly),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Divider(thickness = 6.dp,color = MaterialTheme.colorScheme.background.copy(alpha = 0.0f))

                    SocialMediaRow()
                }
            }
        },
        confirmButton = {
            Button(
                enabled = submitEnabled,
                onClick = { onSubmit(selectedTypeState, feedbackBody) },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.submit),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

@Composable
@Preview(showBackground = true)
fun FeedbackDialogPreview() {
    FeedbackDialog(
        visible = true,
        headerText = "Send Feedback",
        textFieldText = "Enter your feedback here",
        onSubmit = { g, f -> },
        selectedType = ComplaintType.LANGUAGES,
        onDismiss = {}
    )
}

@Composable
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 8.dp
): Modifier {
    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
    val duration = if (state.isScrollInProgress) 150 else 500

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration)
    )

    return drawWithContent {
        drawContent()

        val firstVisibleElementIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
        val needDrawScrollbar = state.isScrollInProgress || alpha > 0.0f

        // Draw scrollbar if scrolling or if the animation is still running and lazy column has content
        if (needDrawScrollbar && firstVisibleElementIndex != null) {
            val elementHeight = this.size.height / state.layoutInfo.totalItemsCount
            val scrollbarOffsetY = firstVisibleElementIndex * elementHeight
            val scrollbarHeight = state.layoutInfo.visibleItemsInfo.size * elementHeight

            drawRect(
                color = Color.Red,
                topLeft = Offset(this.size.width - width.toPx(), scrollbarOffsetY),
                size = Size(width.toPx(), scrollbarHeight),
                alpha = alpha
            )
        }
    }
}

@Composable
fun Modifier.simpleVerticalScrollbar(
    scrollState: ScrollState,
    width: Dp = 8.dp
): Modifier {
    val targetAlpha = if (scrollState.isScrollInProgress) 1f else 0f
    val duration = if (scrollState.isScrollInProgress) 150 else 500

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "scrollbarAlpha"
    )

    return drawWithContent {
        drawContent()

        if (scrollState.maxValue > 0 && (scrollState.isScrollInProgress || alpha > 0f)) {
            val viewHeight = size.height
            val contentHeight = viewHeight + scrollState.maxValue.toFloat()

            // Compute scrollbar position and size
            val proportionVisible = viewHeight / contentHeight
            val scrollbarHeight = proportionVisible * viewHeight
            val scrollbarOffsetY =
                (scrollState.value.toFloat() / scrollState.maxValue.toFloat()) * (viewHeight - scrollbarHeight)

            drawRect(
                color = Color.Red,
                topLeft = Offset(size.width - width.toPx(), scrollbarOffsetY),
                size = Size(width.toPx(), scrollbarHeight),
                alpha = alpha
            )
        }
    }
}
