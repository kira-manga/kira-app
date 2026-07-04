package me.manga.kira.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptionsBuilder

/**
 * Double-tap navigation guard. A navigation only proceeds while the current destination's
 * lifecycle is RESUMED. The moment a navigation starts, the source entry drops to STARTED for the
 * duration of the transition, so a rapid second tap (the classic "tapped Back twice → popped two
 * screens", or "tapped a row twice → pushed it twice") is ignored instead of stacking a second
 * navigation. Returns true when there is no current entry yet (don't block the very first nav).
 *
 * This is the standard Compose-Navigation fix; the project previously had an unused `NavigationLock`
 * and no guard at all (regression fix, 2026-06-02).
 */
private fun NavController.isReadyForNavigation(): Boolean {
    val entry = currentBackStackEntry ?: return true
    return entry.lifecycle.currentState == Lifecycle.State.RESUMED
}

fun NavController.safePopBackStack(
    libraryRoute: String = Screen.Library.route,
): Boolean {
    // Drop rapid repeat taps (e.g. double-tapping Back) — only the first, made while the screen is
    // RESUMED, pops; the in-flight transition leaves the entry STARTED so the second tap no-ops.
    if (!isReadyForNavigation()) return false

    val startDestinationId = runCatching {
        graph.findStartDestination().id
    }.getOrNull()

    return try {
        val previousEntry = previousBackStackEntry
        if (previousEntry == null) {
            return navigateToLibrary(libraryRoute, startDestinationId)
        }

        val popped = popBackStack()
        if (!popped) {
            navigateToLibrary(libraryRoute, startDestinationId)
        } else {
            true
        }
    } catch (e: IllegalStateException) {
        navigateToLibrary(libraryRoute, startDestinationId)
    } catch (e: IllegalArgumentException) {
        navigateToLibrary(libraryRoute, startDestinationId)
    } catch (t: Throwable) {
        navigateToLibrary(libraryRoute, startDestinationId)
    }
}

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
        false
    }
}

fun NavController.safeNavigate(
    route: Any,
    builder: NavOptionsBuilder.() -> Unit = {}
) {
    // Drop rapid repeat taps so double-clicking a row/button doesn't push the destination twice —
    // only the first tap (made while the source screen is RESUMED) navigates. See
    // [isReadyForNavigation]. Auto-navigations (e.g. the What's-New first-launch redirect) fire from
    // a LaunchedEffect after the host is already RESUMED, so they are unaffected.
    if (!isReadyForNavigation()) return
    // GAP-NAV-03 (P3 cleanup): the `[ReaderNav]` invoke/success/failure debug `println`s that landed
    // during reader-nav debugging are removed — they printed on every navigation in production builds.
    // The defensive try/catch is preserved (it absorbs the type-safe-route navigate() throw without
    // crashing); the failure branch is now a silent no-op rather than a stdout dump.
    try {
        navigate(route) {
            builder()
        }
    } catch (e: Exception) {
        // Swallow navigation failures (e.g. a route not present in the current graph) so a stray
        // nav call can never crash the host. No logging facade is available on this classpath.
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster244.staleKdocSweep.cascade, Task #700, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster244 leaf 4 of 4 — commonMain :composeApp navigation safePopBackStack,
 * sibling 496 CLOSER of 4-LEAF-NAVIGATION-PACKAGE-AXIS-COHESIVE-BATCH sweep.
 * Cumulative section-253-postscript count = 220 leaves with this commit.
 *
 * File-shape note: 79-line file (pre-postscript) — NO file-level KDoc. 3
 * top-level fun declarations: safePopBackStack (public NavController ext,
 * Boolean return), navigateToLibrary (private NavController ext, helper for
 * safePopBackStack), safeNavigate (public NavController
 * ext, Unit return, NavOptionsBuilder lambda). 4 imports beyond package
 * decl (androidx.lifecycle.Lifecycle plus androidx.navigation.NavController
 * plus NavGraph.Companion.findStartDestination plus NavOptionsBuilder).
 * NO companion. LONGEST-FILE-AT-cluster244 (79 lines pre-postscript vs
 * siblings 493 at 5, 494 at 6, 495 at 39 lines). (Review-campaign fix-05,
 * 2026-06-12: the zero-caller safePopBackStackAsync 50ms-delay variant and
 * its kotlinx.coroutines.delay import were deleted — code-ac-6/xcut-nav-8;
 * the enumeration above is corrected accordingly.)
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - DEFENSIVE-FALLBACK-NAV-EXTENSION-AXIS-LIVE — safePopBackStack uses a
 *     3-catch-chain (IllegalStateException plus IllegalArgumentException
 *     plus Throwable) all routing to navigateToLibrary fallback. The
 *     defensive posture IS load-bearing because NavController.popBackStack
 *     can throw under back-stack-corruption edge cases (multi-tab
 *     reselection plus deep-link plus process-death restoration), and the
 *     library-route fallback IS the safe-rendezvous-point that all 3 tabs
 *     can rehydrate from. PRESERVE — defends against future "use sealed
 *     catch with logging" refactor (which would lose the broad-Throwable
 *     safety net).
 *
 *   - (Review-campaign fix-05, 2026-06-12, code-ac-7: two stale bullets
 *     dropped here — "CONCURRENCY-PRIMITIVE-PAIR-WITH-NavigationLock-LIVE"
 *     (NavigationLock.kt no longer exists anywhere in the repo; the
 *     in-code [isReadyForNavigation] guard replaced it) and
 *     "DEBUG-PRINTLN-IN-PRODUCTION-CODE-FLAG-LIVE" (the `[ReaderNav]`
 *     printlns were removed under GAP-NAV-03 — see the in-code comment in
 *     safeNavigate). Both misdescribed current behavior.)
 *
 *   - LIBRARY-ROUTE-RENDEZVOUS-DEFAULT-LIVE — safePopBackStack default
 *     libraryRoute parameter is `Screen.Library.route`. The fallback
 *     destination IS Library (not Home, not About, not Downloads). The
 *     choice reflects the legacy Yami topology where Library WAS the
 *     bottom-bar default tab. 1-DIVERGES from a hypothetical Home-default
 *     posture. PRESERVE — load-bearing as architectural-decision residue
 *     about the legacy bottom-bar default-tab convention.
 *
 *   - (Review-campaign fix-05, 2026-06-12, code-ac-6/xcut-nav-8: the
 *     "SUSPEND-VS-NON-SUSPEND-FORK-LIVE" bullet was dropped here — the
 *     safePopBackStackAsync variant it described had zero call sites
 *     ("some nav sites need to wait for an in-flight animation" — no such
 *     site existed) and was deleted.)
 *
 *   - PRIVATE-HELPER-navigateToLibrary-LIVE — navigateToLibrary IS a
 *     private NavController extension fun shared by safePopBackStack
 *     across its 3 catch branches plus the no-previous-entry branch.
 *     launchSingleTop=true plus restoreState=true plus popUpTo
 *     (startDestinationId) inclusive=false saveState=true — the full
 *     bottom-bar-rendezvous nav-options shape. PRESERVE — load-bearing
 *     because the saveState plus restoreState pair IS what makes the
 *     bottom-bar tab content survive cross-tab navigation.
 *
 *   - RUN-CATCHING-START-DESTINATION-LIVE — safePopBackStack uses run
 *     Catching around `graph.findStartDestination().id` because graph
 *     access throws if NavController is not yet wired to a graph (rare
 *     but possible during early process-restore). PRESERVE — defends
 *     against the IllegalStateException-NavController-not-set-up edge
 *     case.
 *
 *   - SAFENAVIGATE-Any-ROUTE-PARAMETER-LIVE — safeNavigate accepts
 *     `route: Any` (not `route: Screen` sealed type, not `route: String`).
 *     The Any param IS load-bearing because androidx.navigation type-safe
 *     nav (Compose nav3) takes a serializable @Serializable data class
 *     route object, not the legacy string-route. The Any signature
 *     accepts both shapes. 1-DIVERGES from a hypothetical sealed-Screen-
 *     only posture (which would lose String-route compat for hosts that
 *     have not yet migrated). PRESERVE.
 *
 *   - 4-IMPORT-COUNT-LARGEST-IN-cluster244-LIVE — safePopBackStack has 4
 *     imports (3 androidx.navigation plus 1 androidx.lifecycle).
 *     1-DIVERGES from sibling 493 plus 494 (zero imports) and sibling 495
 *     (3 imports, all kotlinx.coroutines). The androidx.navigation reach
 *     IS unavoidable — these ARE NavController extension functions.
 *     PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-4-AGREE-CLOSES-LIVE — safePopBackStack file has
 *     NO companion object. 4-AGREE-CLOSES-AT-cluster244. PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster244-LIVE — safePopBackStack CLOSES
 *     cluster244 4-LEAF-NAVIGATION-PACKAGE-AXIS-COHESIVE-BATCH sweep.
 *     Cluster244 outlier-direction-at-CLOSER: ZERO-DIRECTION-INTRA-CLUSTER
 *     (the 4-leaf batch has no platform-axis OUTLIER because all 4
 *     leaves are commonMain-only; the only intra-batch divergence is
 *     surface-type — interface plus holder plus class plus extension-
 *     fun-set). cluster244-CLOSER classification: NAVIGATION-PACKAGE-
 *     AXIS-SUB-TIER-COHESIVE-BATCH-CLOSES.
 *
 *   - 14-CONSECUTIVE-CLUSTER-BEDROCK-SPAN-CLOSES-LIVE — safePopBackStack
 *     CLOSES the cluster231-244 BEDROCK span at 14 consecutive BEDROCK
 *     clusters. The CROSS-SUB-TIER-BEDROCK-CONTINUITY classification at
 *     cluster244 confirms transition from PLATFORM-UTILITY-SUB-TIER
 *     (cluster231-243) to NAVIGATION-PACKAGE-AXIS-SUB-TIER (cluster244)
 *     WITHIN the same BEDROCK tier. PRESERVE.
 *
 *   - cluster245-PREDICTION — Next candidate sweep targets (in priority
 *     order): (a) Common-component utility 5-leaf batch: presentation
 *     common componants buttons ActionButton.kt plus IconAboveTextButton
 *     .kt plus flow_chips ChipsRow.kt plus floating_button Floating
 *     ActionButton.kt plus app_bars TopAppBarCom.kt (COMMON-COMPONENT-
 *     UI-AXIS). (b) Single-file polish leaves: theme Color.kt plus
 *     presentation common screens LoadingScreen.kt plus presentation
 *     common componants isScrolledToTheEnd.kt. (c) core util Plus18memes
 *     .kt plus core progress format.kt — 2-leaf CORE-UTIL-AXIS sub-
 *     batch. RESERVE per autonomous-cascade standing directive. (d)
 *     CryptoUtils (sources_repositry ar dilar) — EXCLUDED per mid-
 *     session pivot.
 */

