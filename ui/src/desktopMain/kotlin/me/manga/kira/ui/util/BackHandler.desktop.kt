package me.manga.kira.ui.util

import androidx.compose.runtime.Composable

/**
 * Desktop (JVM) actual — intentional no-op.
 *
 * Desktop has no Android-style hardware/predictive system-back to intercept; back navigation is
 * surfaced through window chrome / explicit UI affordances and handled by the navigation host.
 * Screens may still call [BackHandler] uniformly from commonMain; on desktop the [onBack] lambda is
 * simply never invoked by this wrapper. (If a desktop key/shortcut should route to back later, this
 * is the single place to wire it.)
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: see KDoc above.
}
