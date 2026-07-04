package me.manga.yamiapk.presentation.common.componants.floating_button

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.with
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A base FAB composable that wraps Material3's FloatingActionButton.
 *
 * @param onClick Lambda to invoke when FAB is clicked.
 * @param icon The ImageVector icon to show inside the FAB.
 * @param contentDescription Description for accessibility.
 * @param modifier Modifier for sizing or positioning.
 * @param containerColor Background color of FAB.
 * @param contentColor Color for the icon/content.
 * @param tonalElevation Elevation (for Material3 tonal surfaces).
 * @param shape Shape if you want to override (default is small FAB shape).
 */
@Composable
fun BaseFloatingActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    tonalElevation: Dp = 4.dp,
    shape: Shape = FloatingActionButtonDefaults.shape
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = tonalElevation),
        shape = shape,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}

/**
 * A base Extended FAB composable that wraps Material3's ExtendedFloatingActionButton.
 *
 * @param onClick Lambda to invoke when FAB is clicked.
 * @param icon Optional icon to show at the start of the text.
 * @param text The label text to display.
 * @param modifier Modifier for sizing or positioning.
 * @param containerColor Background color.
 * @param contentColor Color for icon & text.
 * @param expanded Boolean to control whether to show icon+text or just icon (if you want collapse behavior).
 */
@Composable
fun BaseExtendedFloatingActionButton(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    tonalElevation: Dp = 4.dp,
    shape: Shape = FloatingActionButtonDefaults.extendedFabShape
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = tonalElevation),
        shape = shape,
        modifier = modifier.animateContentSize() // animate size changes
    ) {
        Row {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription
                )
                // Animate visibility of the text + spacer
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(animationSpec = tween(300)) + expandHorizontally(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300)) + shrinkHorizontally(animationSpec = tween(300))
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text)
                }
            } else {
                // No icon: just animate text? Usually collapsed makes no sense without icon, but:
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Text(text)
                }
            }
        }
    }
}


@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedCircleExtendedFab(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector,
    contentDescription: String?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
    tonalElevation: Dp = 4.dp,
) {
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            // You can customize enter/exit transitions here.
            // E.g., fade + size transform:
            fadeIn(animationSpec = tween(200)).togetherWith(fadeOut(animationSpec = tween(200)))
        },
        label = "FAB size switch"
    ) { targetExpanded ->
        if (targetExpanded) {
            // Extended FAB with icon + text
            ExtendedFloatingActionButton(
                onClick = onClick,
                containerColor = containerColor,
                contentColor = contentColor,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = tonalElevation),
                shape = FloatingActionButtonDefaults.extendedFabShape,
                modifier = modifier.animateContentSize() // animate when its size changes
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text)
            }
        } else {
            // Circular FAB with only icon
            FloatingActionButton(
                onClick = onClick,
                containerColor = containerColor,
                contentColor = contentColor,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = tonalElevation),
                shape = CircleShape,
                modifier = modifier // no animateContentSize needed since size is fixed circle
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription
                )
            }
        }
    }
}
