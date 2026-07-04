package me.manga.kira.ui.util

import androidx.compose.runtime.Composable

/**
 * Android actual — delegates to the platform `androidx.activity.compose.BackHandler`, which
 * registers an `OnBackPressedCallback` against the host `OnBackPressedDispatcher` (predictive-back
 * aware). This is the real system-back integration that lets a screen intercept back to close a
 * search overlay / sheet before the navigation host pops.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
