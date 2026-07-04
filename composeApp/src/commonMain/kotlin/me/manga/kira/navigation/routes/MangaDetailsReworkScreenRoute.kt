package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import me.manga.kira.domain.model.Manga
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsViewModel
import me.manga.kira.ui.details.DetailsScreen
import me.manga.kira.ui.details.DetailsScreenByUrl
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Manga Details screen (Phase 8.x).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe
 * `composable<Screen.MangaDetailsRework>`) and the `:ui/.../details/DetailsScreen`
 * composable. Owns the rework [DetailsViewModel] via Koin and translates the screen's
 * `onNavigateBack` / `onNavigateToReader` callbacks into navigation actions.
 *
 * **Scope**: this is the entry point that proves the rework Details slice end-to-end —
 *
 *  1. **Koin DI** (`detailsReworkModule` from `:composeApp/commonMain/di/`) resolves the
 *     `DetailsViewModel` constructor's `factory`-scoped `FetchMangaDetailsUseCase`, which
 *     in turn depends on the `single`-scoped `MangaDetailsRepository` (legacy
 *     `SourcesRepository` from `:shared` + rework `DispatcherProvider`).
 *  2. **`:presentation` MVI** plumbing emits `DetailsState` via `StateFlow` and
 *     `DetailsEffect` via the unbounded `Channel` from the base `MviViewModel`.
 *  3. **`:ui` Compose** renders the state, dispatches intents via `viewModel.submit(...)`,
 *     and forwards `NavigateBack` / `NavigateToReader` effects through this adapter's
 *     nav callbacks.
 *
 * **`Manga` reconstruction from nav args**: the `Screen.MangaDetailsRework` route carries
 * the full identity tuple (api + language + title + url + coverUrl + rating + genres). We
 * rebuild the `Manga` value class once per (api, language, title) tuple so the
 * `LaunchedEffect` keyed on that triple inside the screen doesn't re-fire on every
 * recomposition. The reconstructed `Manga` is feature-complete for the screen's needs:
 * `title` drives the top-bar placeholder; the identity triple drives the VM's `OnEnter`
 * re-entry guard (§43.4 / §43.5); `url` drives the fetch (§42.2).
 *
 * **Reader navigation** — tapping a chapter row emits `DetailsEffect.NavigateToReader(manga,
 * chapter)`. The screen forwards that to this adapter's `onNavigateToReader` callback, which
 * navigates to [Screen.ChapterImagesRework] (Phase 8.x.reader). The chapter row's pure-domain
 * `Chapter` payload is destructured into the route's identity tuple (Manga identity + chapter
 * number / name / url) — see [Screen.ChapterImagesRework] KDoc for which fields the route
 * omits. The legacy `Screen.ChapterImagesFragment` path is unchanged; this adapter does NOT
 * route to the legacy reader (the legacy route's `mangaId` / `chapterId` Room PKs aren't
 * available without an un-bookmarked Saved row).
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: `Screen.MangaDetailsRework` is
 * owned by the `:composeApp` nav graph (it carries the full `Manga` tuple). `:ui`
 * deliberately exposes generic `() -> Unit` / `(Manga, Chapter) -> Unit` callbacks so the
 * screen stays nav-host-agnostic and reusable.
 *
 * **No `BackHandler`**: matches the sibling `LibraryReworkScreenRoute` and legacy
 * `MangaDetailsScreenRoute` deliberately. System back is the default; the cross-platform
 * `BackHandler` gap pending an expect/actual shim is documented at `App.kt` KDoc point 4.
 *
 * @param navController parent nav controller for `safePopBackStack` on back-effect.
 * @param backStackEntry NavBackStackEntry — args are read from here via `toRoute<...>()`;
 *                       the VM is `koinViewModel()`-scoped (process-wide on Android via
 *                       Koin's ViewModelStoreOwner integration).
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
 * 2026-05-28): the "No `BackHandler`" paragraph (lines 58-60 above) cites the sibling legacy
 * `MangaDetailsScreenRoute` as a parity precedent. That file was retired in Phase
 * 9.x.mangadetails.retire (§430, Slice 5 of the Phase 7.x.details.parity campaign) — verified
 * by Glob search for `MangaDetailsScreenRoute.kt` returning zero hits. The sibling
 * `LibraryReworkScreenRoute` reference remains LIVE and continues to encode the rework
 * convention. The "no `BackHandler`" decision stands on its own merits (system back is the
 * default; cross-platform `BackHandler` gap is documented at `App.kt` KDoc point 4); only the
 * sibling legacy citation is historical record now. Original §253-era prose preserved
 * verbatim per §253.
 */
@Composable
fun MangaDetailsReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val viewModel: DetailsViewModel = koinViewModel()

    val args = backStackEntry.toRoute<Screen.MangaDetailsRework>()

    val solveCloudflare = rememberCloudflareChallengeSolver(
        navController = navController,
        ownerEntry = backStackEntry,
        onRetry = { viewModel.submit(DetailsIntent.OnRetry) },
    )

    val manga = Manga(
        api = args.api,
        language = args.language,
        title = args.title,
        url = args.url,
        coverUrl = args.coverUrl,
        rating = args.rating,
        genres = args.genres,
    )

    DetailsScreen(
        viewModel = viewModel,
        manga = manga,
        onNavigateBack = { navController.safePopBackStack() },
        onNavigateToReader = { mangaArg, chapter ->
            navController.safeNavigate(
                Screen.ChapterImagesRework(
                    api = mangaArg.api,
                    language = mangaArg.language,
                    title = mangaArg.title,
                    mangaUrl = mangaArg.url,
                    coverUrl = mangaArg.coverUrl,
                    chapterNumber = chapter.number,
                    chapterName = chapter.name,
                    chapterUrl = chapter.url,
                ),
            )
        },
        // Phase 7.x.details.downloads §253 / ADR-3: Details top-bar Downloads button routes to
        // the rework Downloads screen, not the legacy Library-tab quirk. Intentional UX change
        // documented in the slice's commit + ADR-3.
        onNavigateToDownloads = { navController.safeNavigate(Screen.DownloadsRework) },
        // Phase 7.x.details.webview §253 / ADR-5: Details top-bar ↗ button + the error-pane
        // "Open in WebView" fallback both route here. `:composeApp` is the only layer that
        // knows the destination is the legacy `Screen.WebView(url, api)`; `:presentation`
        // emits the effect in domain terms (`NavigateToWebView`) and `:ui` exposes a generic
        // `(url, api) -> Unit` callback — see ChapterImagesReworkScreenRoute.kt:117 for the
        // sibling precedent. No rework WebView screen exists yet; the rework adapter owning
        // the only call site means a future ownership flip is a one-file edit.
        onOpenInWebView = { url, api -> navController.safeNavigate(Screen.WebView(url = url, api = api)) },
        // Bug #2 (legacy Handle403Error parity): a 403 fetch failure routes here to solve the
        // Cloudflare challenge, then auto-retries the fetch when control returns to Details.
        onSolveCloudflareChallenge = solveCloudflare,
    )
}

/**
 * Route host for the URL-only entry shape (Phase 9.x.mangadetails.swap Slice 4 — ADR-6).
 *
 * Adapter between the legacy `composable<Screen.MangaDetails>` block in `App.kt` (carries
 * `(mangaUrl, api)` only — the legacy two-tuple shape) and the rework
 * [me.manga.kira.ui.details.DetailsScreenByUrl] composable. Owns the same rework
 * [DetailsViewModel] as [MangaDetailsReworkScreenRoute] — the VM serves both entry shapes
 * (handles [me.manga.kira.presentation.details.DetailsIntent.OnEnter] AND
 * [me.manga.kira.presentation.details.DetailsIntent.OnEnterByUrl] symmetrically).
 *
 * **Why a sibling route adapter, not a parameter on the existing one**: a parameter would force
 * every caller of [MangaDetailsReworkScreenRoute] to specify which entry shape — couples the
 * 4 legacy caller nav sites to the rework adapter's internals. A sibling top-level fun keeps the
 * two entry shapes as separate route hosts with distinct signatures, matching how
 * `composable<Screen.X>` blocks read `(api, mangaUrl)` from one route arg shape versus the
 * full identity tuple from the other. SRP: each adapter handles one route's args shape.
 *
 * **No new `Screen.X` route**: this adapter binds against the existing legacy `Screen.MangaDetails`
 * (kept in `Screen.kt` per ADR-7 / ADR-8 — the legacy route key stays, the legacy *screen* goes
 * in Slice 5). The 4 caller nav sites that emit `Screen.MangaDetails(mangaUrl, api)` continue
 * working unchanged; only the `composable<Screen.MangaDetails>` binding in `App.kt` flips from
 * the legacy `MangaDetailsScreenRoute` to this rework adapter.
 *
 * **Top-bar title fallback**: until the fetch lands and enriches `state.manga.title`, the screen's
 * top-bar title is blank (~200-500 ms typical). Acceptable parity with the legacy screen, which
 * also rendered a placeholder header until its own fetch resolved. The bookmark IconButton is
 * additionally gated on `state.manga?.title?.isNotBlank() == true` (DetailsScreen.kt §253) to
 * prevent toggling against the URL-only sentinel before identity resolves.
 *
 * @param navController parent nav controller for `safePopBackStack` on back-effect.
 * @param backStackEntry NavBackStackEntry — args are read here via `toRoute<Screen.MangaDetails>()`;
 *                       the VM is `koinViewModel()`-scoped.
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
 * 2026-05-28): the "No new `Screen.X` route" paragraph (lines 136-140 above) narrates the
 * swap from "the legacy `MangaDetailsScreenRoute`" to "this rework adapter" via the
 * `composable<Screen.MangaDetails>` binding in `App.kt`. The swap landed in Phase
 * 9.x.mangadetails.swap (§429, Slice 4 of the Phase 7.x.details.parity campaign) and the
 * legacy `MangaDetailsScreenRoute.kt` was deleted in Phase 9.x.mangadetails.retire (§430,
 * Slice 5) — verified by Glob search for `MangaDetailsScreenRoute.kt` returning zero hits. This
 * adapter is now the SOLE binding for `composable<Screen.MangaDetails>` in `App.kt`. ADR-7
 * (keep `Screen.MangaDetailsRework` alongside `Screen.MangaDetails`) and ADR-8 (keep the
 * legacy `Screen.MangaDetails` route key in `Screen.kt` post-retire) both stand — the legacy
 * route KEY remains, the legacy SCREEN is gone, and the 4 caller nav sites still emit
 * `Screen.MangaDetails(mangaUrl, api)` unchanged. Original §253-era prose preserved verbatim
 * per §253 — the design lineage of the swap+retire is historical record.
 */
@Composable
fun MangaDetailsByUrlReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val viewModel: DetailsViewModel = koinViewModel()

    val args = backStackEntry.toRoute<Screen.MangaDetails>()

    val solveCloudflare = rememberCloudflareChallengeSolver(
        navController = navController,
        ownerEntry = backStackEntry,
        onRetry = { viewModel.submit(DetailsIntent.OnRetry) },
    )

    DetailsScreenByUrl(
        viewModel = viewModel,
        api = args.api,
        mangaUrl = args.mangaUrl,
        onNavigateBack = { navController.safePopBackStack() },
        onNavigateToReader = { mangaArg, chapter ->
            navController.safeNavigate(
                Screen.ChapterImagesRework(
                    api = mangaArg.api,
                    language = mangaArg.language,
                    title = mangaArg.title,
                    mangaUrl = mangaArg.url,
                    coverUrl = mangaArg.coverUrl,
                    chapterNumber = chapter.number,
                    chapterName = chapter.name,
                    chapterUrl = chapter.url,
                ),
            )
        },
        onNavigateToDownloads = { navController.safeNavigate(Screen.DownloadsRework) },
        onOpenInWebView = { url, api -> navController.safeNavigate(Screen.WebView(url = url, api = api)) },
        // Bug #2 (legacy Handle403Error parity): a 403 fetch failure routes here to solve the
        // Cloudflare challenge, then auto-retries the fetch when control returns to Details.
        onSolveCloudflareChallenge = solveCloudflare,
    )
}

