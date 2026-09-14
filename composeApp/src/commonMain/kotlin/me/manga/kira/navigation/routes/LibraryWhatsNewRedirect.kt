package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import me.manga.kira.navigation.Screen
import me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Pending navigation belongs to this Library entry, not whichever destination happens to resume. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
internal fun LibraryWhatsNewRedirect(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val viewModel: WhatsNewViewModel = koinViewModel(viewModelStoreOwner = backStackEntry)
    LaunchedEffect(navController, backStackEntry, viewModel) {
        combine(
            viewModel.shouldShowWhatsNew,
            navController.currentBackStackEntryFlow,
            backStackEntry.lifecycle.currentStateFlow,
        ) { pending, current, state ->
            pending && current === backStackEntry && state == Lifecycle.State.RESUMED
        }.collect { eligible ->
            if (eligible && viewModel.shouldShowWhatsNew.value && navController.tryOpenWhatsNew(backStackEntry)) {
                // No suspension between the actual push and entry-local suppression. The destination
                // alone persists the automatic mark after its first successful load in that mount (including empty).
                viewModel.onAutoNavigationSucceeded()
            }
        }
    }
}

/** Fixed-route attempt; a refused/failed push must not consume the pending Library opportunity. */
internal fun NavController.tryOpenWhatsNew(owner: NavBackStackEntry): Boolean {
    if (currentBackStackEntry !== owner || owner.lifecycle.currentState != Lifecycle.State.RESUMED) return false
    return try {
        navigate(Screen.WhatsNewScreen(isFirstOpen = true))
        val pushed = currentBackStackEntry
        pushed != null &&
            pushed !== owner &&
            pushed.destination.hasRoute<Screen.WhatsNewScreen>() &&
            pushed.toRoute<Screen.WhatsNewScreen>().isFirstOpen
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
