package me.manga.yamiapk.navigation

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.delay

/**
 * Safely pop the back stack with proper error handling and focus clearing.
 *
 * This prevents common crashes:
 * - NullPointerException in FocusFinder (TextField focus during navigation)
 * - IllegalArgumentException (view hierarchy issues)
 * - IndexOutOfBoundsException (empty back stack)
 * - IllegalStateException (concurrent navigation)
 *
 * @param libraryRoute The fallback route if popping fails (default: Library screen)
 * @param clearFocus Whether to clear focus before popping (recommended: true)
 * @return true if pop was successful, false if navigated to library instead
 */
@MainThread
fun NavController.safePopBackStack(
    libraryRoute: String = Screen.Library.route,
    clearFocus: Boolean = true
): Boolean {
    // Clear focus to prevent view hierarchy crashes
    if (clearFocus) {
        try {
            clearAllFocus(context)
        } catch (e: Exception) {
            // Ignore focus clearing errors - they're not critical
        }
    }

    val startDestinationId = runCatching {
        graph.findStartDestination().id
    }.getOrNull()

    return try {
        // Check if there's a previous entry to pop to
        // previousBackStackEntry is null when we're at the start destination
        val previousEntry = previousBackStackEntry
        if (previousEntry == null) {
            // No previous entry - we're at the start destination
            // Navigate to library instead of popping (which would do nothing)
            return navigateToLibrary(libraryRoute, startDestinationId)
        }

        // Attempt to pop back stack
        val popped = popBackStack()
        if (!popped) {
            // Pop failed (shouldn't happen if previousEntry exists, but defensive programming)
            navigateToLibrary(libraryRoute, startDestinationId)
        } else {
            true
        }
    } catch (e: IllegalStateException) {
        // Entry not in back stack - already removed during concurrent operation
        // This can happen with predictive back gestures or rapid navigation
        navigateToLibrary(libraryRoute, startDestinationId)
    } catch (e: IllegalArgumentException) {
        // Invalid navigation state
        navigateToLibrary(libraryRoute, startDestinationId)
    } catch (t: Throwable) {
        // Any other error - fallback to library
        // Log this for debugging
        android.util.Log.e("SafeNavigation", "Unexpected error in safePopBackStack", t)
        navigateToLibrary(libraryRoute, startDestinationId)
    }
}

/**
 * Navigate to library screen as a fallback when popping fails.
 */
private fun NavController.navigateToLibrary(
    libraryRoute: String,
    startDestinationId: Int?
): Boolean {
    return try {
        navigate(libraryRoute) {
            launchSingleTop = true
            restoreState = true
            startDestinationId?.let {
                popUpTo(it) {
                    inclusive = false
                    saveState = true
                }
            }
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("SafeNavigation", "Failed to navigate to library", e)
        false
    }
}

/**
 * Clear all focus from the current activity's view hierarchy.
 * This prevents crashes when views are removed while they have focus.
 */
private fun clearAllFocus(context: Context) {
    val activity = context as? Activity
    val decorView = activity?.window?.decorView
    decorView?.clearFocus()
    decorView?.rootView?.clearFocus()
}

/**
 * Suspend version of safePopBackStack for use in coroutines.
 * Adds a small delay to let the current composition frame complete.
 *
 * @param libraryRoute The fallback route if popping fails
 * @param clearFocus Whether to clear focus before popping
 * @param delayMs Milliseconds to delay before popping (allows current frame to complete)
 * @return true if pop was successful, false if navigated to library instead
 */
suspend fun NavController.safePopBackStackAsync(
    libraryRoute: String = Screen.Library.route,
    clearFocus: Boolean = true,
    delayMs: Long = 50
): Boolean {
    // Small delay to let current frame complete
    // This helps prevent race conditions with ongoing animations
    delay(delayMs)
    return safePopBackStack(libraryRoute, clearFocus)
}

/**
 * Safe navigation wrapper that clears focus before navigating.
 * Use this for all navigation operations to prevent focus-related crashes.
 *
 * @param route The destination route
 * @param clearFocus Whether to clear focus before navigating
 * @param builder Optional navigation options builder
 */
fun NavController.safeNavigate(
    route: Any,
    clearFocus: Boolean = true,
    builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}
) {
    if (clearFocus) {
        try {
            clearAllFocus(context)
        } catch (e: Exception) {
            // Ignore
        }
    }

    try {
        navigate(route) {
            builder()
        }
    } catch (e: Exception) {
        android.util.Log.e("SafeNavigation", "Navigation failed", e)
    }
}