package me.manga.kira.ui.util

import androidx.compose.runtime.Composable

/**
 * iOS actual — intentional no-op.
 *
 * iOS has no Android-style hardware/predictive system-back to intercept; navigation back is driven
 * by the interactive edge-swipe gesture and nav-bar chrome, handled by the navigation host rather
 * than an in-screen handler. Screens may still call [BackHandler] uniformly from commonMain; on iOS
 * the [onBack] lambda is simply never invoked by this wrapper. (If predictive-back routing is needed
 * on iOS later, this is the single place to wire it.)
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: see KDoc above.
}
