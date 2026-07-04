package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.features.webview.ui.screens.WebViewComposeScreen
import me.manga.kira.presentation.features.webview.ui.viewmodel.WebViewViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the embedded WebView screen used to authenticate against a manga site /
 * capture session cookies for the source repo.
 *
 * Deltas vs upstream `WebViewScreen` Composable wrapper:
 *   1. `hiltViewModel()` -> `koinViewModel<WebViewViewModel>()` (registered in `SharedModule.kt`).
 *   2. The route's `url` / `api` arguments are decoded via `backStackEntry.toRoute<Screen.WebView>()`
 *      (compose-navigation typed routes), matching the rest of the navigation tree.
 *   3. `onBackPressed.invoke()` -> `navController.safePopBackStack()` (Phase 9.2).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster88.staleKdocSweep.cascade,
 * Task #544, 2026-05-28): the 3-delta upstream-port manifest above
 * is classified as follows after recursive symbol verification
 * across the KMP graph (thirtieth sibling of the cluster57-87 sweep
 * — second route-adapter file visited in the `navigation/routes/`
 * cluster, sibling of cluster87's StatisticsReworkScreenRoute):
 *  (a) Delta #1 — FULFILLED-PREDICTION. "`hiltViewModel()` rename
 *  to `koinViewModel<WebViewViewModel>()` (registered in
 *  `SharedModule.kt`)" — LIVE realization at L10-11 imports
 *  (`me.manga.kira.presentation.features.webview.ui.viewmodel.
 *  WebViewViewModel` plus `org.koin.compose.viewmodel.koinViewModel`)
 *  plus L29 (`val webViewViewModel: WebViewViewModel = koinView-
 *  Model()`). Recursive Grep for `hiltViewModel` matches ZERO live
 *  references file-wide.
 *  (b) Delta #2 — FULFILLED-PREDICTION. "The route's `url` / `api`
 *  arguments are decoded via `backStackEntry.toRoute<Screen.WebView>
 *  ()` (compose-navigation typed routes), matching the rest of the
 *  navigation tree" — LIVE realization at L6 import (`androidx.
 *  navigation.toRoute`) plus L28 (`val args = backStackEntry.
 *  toRoute<Screen.WebView>()`) plus L32-33 (`api = args.api` plus
 *  `initialUrl = args.url`). Typed-routes shape uniform across the
 *  `navigation/routes/` cluster.
 *  (c) Delta #3 — FULFILLED-PREDICTION. "`onBackPressed.invoke()`
 *  rename to `navController.safePopBackStack()` (Phase 9.2)" — LIVE
 *  realization at L8 import (`me.manga.kira.navigation.safePop-
 *  BackStack`) plus L39 (`navController.safePopBackStack()`).
 *  Recursive Grep for `onBackPressed` matches ZERO live references
 *  file-wide; the Phase 9.2 safe-pop extension is the campaign-wide
 *  back-handler pattern across the route-adapter cluster.
 *  Three FULFILLED-PREDICTION classifications STAND on their own
 *  merits as a faithful WebView route-adapter migration manifest.
 *  Original Phase 9.2-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
@Composable
fun WebViewScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val args = backStackEntry.toRoute<Screen.WebView>()
    val webViewViewModel: WebViewViewModel = koinViewModel()

    WebViewComposeScreen(
        api = args.api,
        initialUrl = args.url,
        onSaveHeaders = { headers, api ->
            webViewViewModel.saveHeaders(headers, api)
        },
        onClose = { headers, api ->
            webViewViewModel.saveHeaders(headers, api)
            navController.safePopBackStack()
        },
    )
}
