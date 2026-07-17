package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.usecase.sourceaccess.ObserveSourceAccessUseCase
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.presentation.home.HomeIntent
import me.manga.kira.presentation.home.HomeViewModel
import me.manga.kira.presentation.search.SearchViewModel
import me.manga.kira.ui.home.HomeScreen
import me.manga.kira.ui.search.SearchScreen
import me.manga.kira.ui.sourceaccess.ActivatedHomeSourcePrompt
import me.manga.kira.ui.sourceaccess.LockedHomeSourcePrompt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the user-facing `Screen.Home` entry — the rework Home + Search surface (Epic H5a).
 *
 * **Overlay-swap (legacy parity)**: legacy `HomeScreenRoute` toggled `HomeScreen`/`SearchScreen` on
 * `MangaViewModel.isSearching` without making Search a separate `NavHost` destination — toggling
 * search just swapped the content of the Home backstack entry. This adapter reproduces that exactly:
 * it observes the rework [HomeViewModel]'s `HomeState.isSearching` and renders [SearchScreen] over
 * [HomeScreen] when true, [HomeScreen] otherwise. Both VMs are `koinViewModel()`-scoped to this
 * Home backstack entry so the Search overlay and Home share the same source-tab / active-source
 * context, and dismissing Search returns to the same Home grid state. The system-back-while-searching
 * intercept is deferred (same gap the legacy route documented); the Search top-bar close button
 * (`SearchEffect.Close` → here flips Home's `OnToggleSearch`) is the canonical way to leave the
 * overlay.
 *
 * **Effect → `Screen.X` mapping** (the H4 screens own effect collection internally and surface them
 * as the navigation lambdas below; `:presentation`/`:ui` stay route-agnostic, the guardrail every
 * rework adapter follows):
 *  - `HomeEffect.NavigateToDetails(api, mangaUrl)` / `SearchEffect.NavigateToDetails` →
 *    `Screen.MangaDetailsRework(api, language, title, url, coverUrl, rating, genres)` — the
 *    full-tuple rework Details route (GAP-HOME-21: corrected from the stale `Screen.MangaDetails`
 *    reference; legacy `Screen.MangaDetails` was retired in the Details parity campaign). Forwarding
 *    the complete identity lets Details bind library membership / title / cover up-front rather than
 *    refetching against a blank placeholder.
 *  - `HomeEffect.NavigateToReader(...)` → `Screen.ChapterImagesFragment(...)`, filling the legacy
 *    arg tuple from the effect (`mangaId = 0L`, `chapterId = 0L`, `paths = null`, `isHome = false`).
 *    Per R4 (ADR-8) `Screen.ChapterImagesFragment` now hosts the **rework** Reader
 *    ([ChapterImagesByLegacyArgsReworkScreenRoute]), so a Home chapter-chip tap lands on the rework
 *    reader with full feature parity.
 *  - `HomeEffect.NavigateToWebView(url, api)` → `Screen.WebView(url, api)` (the legacy in-app
 *    browser; Epic W will port it). The rework `HomeViewModel.onOpenWebView` emits a blank `url`
 *    scoped to the active source `api`; the legacy `Screen.WebView` route resolves the concrete
 *    source landing URL from the `api` when the url is blank (the same `WebViewComposeScreen`
 *    behaviour the legacy Home relied on via `getCurrentBaseUrl()`), so no extra resolution is
 *    needed at this adapter.
 *  - `HomeEffect.NavigateToSources` → `Screen.RepoSettings(false)` — the live edit-sources route
 *    (the same target legacy Home navigated to via `Screen.RepoSettings(false)`; the §285 swap
 *    re-pointed `Screen.RepoSettings`'s composable block at the rework Sources screen, so the user
 *    lands on the rework sources manager).
 *  - `SearchEffect.Close` → flips Home's `HomeIntent.OnToggleSearch` so the overlay-swap leaves
 *    Search and re-renders Home (Search is not its own destination — there's nothing to pop).
 *  - `ShowError` → handled inside the H4 screens as a Scaffold snackbar (the screens own a
 *    `SnackbarHostState` + an error-message vocabulary); the adapter doesn't surface it.
 *
 * **Per-source cover slot**: the `:ui` screens take a `coverModel: (HomeFeedItem) -> Any?` slot so
 * the source-aware Coil request (`rememberSourceImageRequest`) can stay in `:composeApp` (it depends
 * on legacy `:shared` types). Here we pass `{ it.coverUrl }`. NOTE: the H4 leaf components currently
 * coerce the slot result with `as? String ?: item.coverUrl`, so a full Coil `ImageRequest` object
 * would be discarded — passing the raw cover URL is the honest wiring today. Per-source auth headers
 * still attach via the singleton Coil `ImageLoader`'s host-match interceptor + OkHttp fetcher wired
 * in `App.kt` (the same path the rework Library/Details covers use), so Cloudflare-protected covers
 * authenticate without threading the request object through. Promoting the slot to accept a real
 * `ImageRequest` (so `rememberSourceImageRequest` flows end-to-end) is a follow-on `:ui` change.
 *
 * **Bottom bar**: Home is a bottom-nav destination, so the bar stays visible on both the Home grid
 * and the Search overlay (the overlay is the same backstack entry). `App.kt` derives this directly
 * from the active destination hierarchy.
 *
 * @param navController parent nav controller for forwarding Details / Reader / WebView / Sources.
 * @param backStackEntry passed for parity with sibling adapters; both VMs are `koinViewModel()`-scoped.
 */
@Composable
fun HomeReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val homeViewModel: HomeViewModel = koinViewModel()
    val searchViewModel: SearchViewModel = koinViewModel()
    val observeSourceAccess: ObserveSourceAccessUseCase = koinInject()

    val homeState by homeViewModel.state.collectAsState()
    val sourceAccessState by observeSourceAccess().collectAsState()
    // A destination is composed at STARTED while the default NavHost transition is still running.
    // safeNavigate intentionally rejects actions before RESUMED, so keep the CTA disabled for that
    // brief interval instead of accepting a tap that would appear to do nothing.
    val lifecycleState by backStackEntry.lifecycle.currentStateAsState()
    val startReadingEnabled = lifecycleState == Lifecycle.State.RESUMED

    // Source-aware cover slot kept in :composeApp (legacy types). The H4 leaf components honor only
    // a String result today, so the raw cover URL is what flows through; per-source auth headers
    // attach at the Coil ImageLoader level (App.kt). See file KDoc.
    val coverModel: (HomeFeedItem) -> Any? = { it.coverUrl }

    if (shouldShowNoSourceFallback(homeState)) {
        when (sourceAccessState) {
            SourceAccessState.LOCKED -> LockedHomeSourcePrompt(
                startReadingEnabled = startReadingEnabled,
                onStartReading = {
                    navController.safeNavigate(Screen.StartReading(onboarding = false))
                },
            )

            SourceAccessState.ACTIVATED -> ActivatedHomeSourcePrompt(
                onEditSources = {
                    navController.safeNavigate(Screen.RepoSettings(isFirstOpen = false))
                },
            )
        }
    } else if (homeState.isSearching) {
        SearchScreen(
            viewModel = searchViewModel,
            onNavigateToDetails = { dest ->
                // Full-tuple rework route → DetailsIntent.OnEnter. The search result carries the
                // complete identity, so Details binds library membership / title / cover up-front
                // instead of refetching against a blank placeholder (bug #1 fresh-state fix).
                navController.safeNavigate(
                    Screen.MangaDetailsRework(
                        api = dest.api,
                        language = dest.language,
                        title = dest.title,
                        url = dest.mangaUrl,
                        coverUrl = dest.coverUrl,
                        rating = dest.rating,
                        genres = dest.genres,
                    ),
                )
            },
            // Search is an overlay on the Home backstack entry, not its own destination — leaving it
            // is a Home state flip, not a back-stack pop.
            onClose = { homeViewModel.submit(HomeIntent.OnToggleSearch) },
            // Search-results error-state actions (F3 native parity), mirroring the Home block's
            // open-in-webview / help wiring. Open-in-WebView mirrors HomeViewModel.onOpenWebView:
            // the active tab's baseUrl scoped to its `api` (nothing resolves a blank url from the
            // api — see HomeViewModel.kt's onOpenWebView comment); no-op when there is no active
            // tab. The active source is the Home backstack entry's `activeTab` — shared with the
            // Search overlay since both VMs are koinViewModel()-scoped to this entry. Help opens
            // the help video in the in-app WebView, identical to the Home block's `onHelp`
            // (source-agnostic url).
            onOpenInWebView = {
                val tab = homeState.activeTab
                if (tab != null) {
                    navController.safeNavigate(
                        Screen.WebView(url = tab.baseUrl, api = tab.api),
                    )
                }
            },
            onHelp = {
                navController.safeNavigate(
                    Screen.WebView(url = "https://kiramanga.me/video/help_video.mp4", api = ""),
                )
            },
            coverModel = coverModel,
        )
    } else {
        HomeScreen(
            viewModel = homeViewModel,
            onNavigateToDetails = { dest ->
                // Full-tuple rework route → DetailsIntent.OnEnter. The feed item carries the
                // complete identity, so Details binds library membership / title / cover up-front
                // instead of refetching against a blank placeholder (bug #1 fresh-state fix). This
                // restores the legacy behaviour where a saved Home row opened its existing manga
                // state immediately rather than looking freshly fetched.
                navController.safeNavigate(
                    Screen.MangaDetailsRework(
                        api = dest.api,
                        language = dest.language,
                        title = dest.title,
                        url = dest.mangaUrl,
                        coverUrl = dest.coverUrl,
                        rating = dest.rating,
                        genres = dest.genres,
                    ),
                )
            },
            onNavigateToReader = { reader ->
                navController.safeNavigate(
                    Screen.ChapterImagesFragment(
                        isHome = false,
                        api = reader.api,
                        language = reader.language,
                        mangaId = 0L,
                        chapterId = 0L,
                        mangatitle = reader.title,
                        mangaUrl = reader.mangaUrl,
                        mangaImgUrl = reader.coverUrl,
                        chapterNumber = reader.chapterNumber,
                        chapterUrl = reader.chapterUrl,
                        paths = null,
                        isDownload = reader.isDownloaded,
                    ),
                )
            },
            onOpenWebView = { url, api ->
                navController.safeNavigate(Screen.WebView(url = url, api = api))
            },
            onNavigateToSources = {
                val destination = if (sourceAccessState == SourceAccessState.ACTIVATED) {
                    Screen.RepoSettings(false)
                } else {
                    Screen.StartReading(onboarding = false)
                }
                navController.safeNavigate(destination)
            },
            onHelp = {
                // GAP-HOME-01 / GAP-HOME-26: the legacy Help affordance opened a full-screen
                // `HelpVideoDialog` streaming an MP4 via Android `VideoView` (not portable). The
                // cross-platform substitute opens the help video URL in the in-app WebView — same
                // content, no KMP media stack. `api` is left blank (the help URL is source-agnostic).
                navController.safeNavigate(
                    Screen.WebView(url = "https://kiramanga.me/video/help_video.mp4", api = ""),
                )
            },
            coverModel = coverModel,
        )
    }
}

internal fun shouldShowNoSourceFallback(
    state: me.manga.kira.presentation.home.HomeState,
): Boolean =
    state.feed.isEmpty() &&
        state.feedError is AppError.Validation.NoEnabledSources
