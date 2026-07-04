package me.manga.kira.ui.util

import androidx.compose.runtime.Composable

/**
 * Multiplatform system-back handler usable from `:ui` commonMain screens.
 *
 * Foundational plumbing for restoring native system-back behavior (e.g. closing a search overlay,
 * dismissing a sheet, popping an in-screen sub-state before the navigation host handles back).
 *
 * ## Why a thin wrapper instead of the Compose-MP common `BackHandler`?
 * Compose Multiplatform 1.11.0 DOES ship a common `androidx.compose.ui.backhandler.BackHandler`,
 * but it is unsuitable as the canonical call-site for screens right now:
 *  - it is annotated `@Deprecated("Use NavigationEventHandler instead")` and slated for removal;
 *  - it is `@ExperimentalComposeUiApi`, forcing an opt-in at every call-site;
 *  - on non-Android targets it reads `LocalCompatNavigationEventDispatcherOwner` and throws
 *    `error("No NavigationEventDispatcher was provided ...")` when that owner is not wired into the
 *    composition — i.e. it crashes on a plain iOS/desktop composition.
 *
 * This wrapper gives screens a single, stable, non-experimental import. Android delegates to the
 * proven `androidx.activity.compose.BackHandler` (real `OnBackPressedDispatcher` integration);
 * iOS and desktop are no-ops because those platforms surface back differently (swipe-back gesture /
 * window chrome) and have no Android-style hardware/predictive back to intercept here.
 *
 * Call-sites use exactly:
 * ```
 * BackHandler(enabled = isOverlayOpen) { closeOverlay() }
 * ```
 *
 * @param enabled when `false`, the handler is inert and back propagates to the next handler / the
 *   platform default. Defaults to `true`.
 * @param onBack invoked when a system-back event is consumed by this handler (Android only today).
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
