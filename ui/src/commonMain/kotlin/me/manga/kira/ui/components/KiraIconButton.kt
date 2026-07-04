package me.manga.kira.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standard icon-only button for the Yami design system (Phase 11.ui.UP-2).
 *
 * Thin wrapper over Material 3 [IconButton] + [Icon] so screens declare actions as
 * `KiraIconButton(KiraIcons.Back, "Back", onBack)` instead of repeating the `IconButton { Icon(..) }`
 * boilerplate and hand-rolling `contentDescription` per call site. Centralising it also guarantees
 * every action carries an accessibility label.
 *
 * @param icon vector from [KiraIcons] (the semantic icon map).
 * @param contentDescription accessibility label; `null` only for icons that are purely decorative
 *        and already described by adjacent text.
 * @param tint defaults to the ambient content colour; override for semantic accents (e.g. a red
 *        favourite heart on the library card).
 */
@Composable
fun KiraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}
