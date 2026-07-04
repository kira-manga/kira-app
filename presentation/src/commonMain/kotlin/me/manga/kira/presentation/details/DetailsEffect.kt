package me.manga.kira.presentation.details

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [DetailsViewModel] for the view to perform once and forget.
 *
 * Strict MVI: effects carry only the trigger (navigation target, typed error). Recurrent UI
 * state (the fetched details, error banners, the loading spinner) lives in [DetailsState] so
 * configuration changes don't replay them.
 */
sealed interface DetailsEffect : MviEffect {

    /** View should pop the Details screen off the navigation stack. */
    data object NavigateBack : DetailsEffect

    /**
     * View should navigate to the Reader screen for [chapter] of [manga]. The Reader feature
     * (future slice) owns chapter-page fetching — Details only points at the chapter, never
     * loads pages.
     */
    data class NavigateToReader(val manga: Manga, val chapter: Chapter) : DetailsEffect

    /** View should show a non-blocking error toast / snackbar. */
    data class ShowError(val error: AppError) : DetailsEffect

    /**
     * View should navigate to the Downloads screen.
     *
     * Effect carries no payload — Downloads is a global list, not per-manga. The `:composeApp`
     * adapter translates this into `safeNavigate(Screen.DownloadsRework)`. Intentional UX
     * change from legacy (which routed to `Screen.Library` with `popUpTo` — a quirk because
     * legacy hosted Downloads inside the Library tab). The rework has a dedicated
     * `Screen.DownloadsRework` route; routing there matches the rework's topology. ADR-3.
     *
     * Phase 7.x.details.downloads §253.
     */
    data object NavigateToDownloads : DetailsEffect

    /**
     * View should navigate to the WebView screen for [url], scoped to source [api].
     *
     * Effect carries the destination payload in *domain terms* — a manga URL plus the source-id
     * the request must be auth-scoped against. The `:composeApp` adapter is the only layer that
     * knows this maps to `Screen.WebView(url, api)`; `:presentation` and `:ui` never name the
     * route. This preserves the campaign clean-architecture guardrail (point 1: `:ui` callbacks
     * are route-agnostic; point 2: effects are destination descriptors, not `Screen.X` references).
     *
     * Emitted by both the top-bar ↗ button (success state) and the error-pane "Open in WebView"
     * fallback (error state) — both surfaces dispatch [DetailsIntent.OnOpenInWebView] with the
     * same payload derived from `state.manga.url` + `state.manga.api`. ADR-5.
     *
     * Legacy parity: legacy `onOpenInWebViewError` defaulted to `viewModel.currentUrl` and
     * returned early when empty (matched `MangaDetailsScreenRoute.kt:94-101`). Rework reads
     * `state.manga?.url` — same observable behaviour (no emit when manga identity is null).
     *
     * Phase 7.x.details.webview §253 / ADR-5.
     *
     * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
     * 2026-05-28): the cited `MangaDetailsScreenRoute.kt:94-101` reference points at a file
     * that was retired in Phase 9.x.mangadetails.retire (§430, Slice 5 of the
     * Phase 7.x.details.parity campaign). The legacy adapter is gone; this rework effect is
     * now the SOLE WebView-navigation surface for the Details screen, emitted by both the
     * top-bar ↗ button and the error-pane "Open in WebView" fallback. The behavioural-parity
     * rule (no emit when manga identity is null) stands on its own merits — it's encoded in
     * `DetailsViewModel.onOpenInWebView()` as `state.value.manga ?: return`. Verified by Glob
     * search for `MangaDetailsScreenRoute.kt` returning zero hits. Original prose preserved verbatim
     * per §253 — the legacy comparison is historical record of the design lineage.
     */
    data class NavigateToWebView(val url: String, val api: String) : DetailsEffect

    /**
     * Fetch failed with an HTTP 403 — almost always a Cloudflare / anti-bot interstitial that the
     * source serves until the user solves a browser challenge (which sets the session cookies the
     * source then expects on its HTML + image requests).
     *
     * Legacy parity: the old `MangaDetailsRoute` wrapped the screen in `Handle403Error`, which on a
     * `State.Error(code == 403)` popped a `WebViewDialog` for [url]/[api]; once the user dismissed
     * it (challenge solved → cookies stored on the per-source header store), the route auto-retried
     * the fetch (`mangaDerailsViewModel.onRetry(...)` after a 1s delay). The rework dropped that
     * path during the migration, so a Cloudflare-protected source's first open returned a plain
     * "failed to load", and tapping Retry hit the same 403 again — the user-reported "repeated
     * failed to load" (bug #2).
     *
     * This effect restores that behaviour in domain terms: the VM emits it (instead of, not in
     * addition to, the generic `ShowError` snackbar) when the failure is a 403. The `:composeApp`
     * adapter maps it to `Screen.WebView(url, api)` — the rework's WebView solves the challenge and
     * primes the same per-source header store the cover/HTML fetch uses. The `:ui` layer auto-
     * re-dispatches [DetailsIntent.OnRetry] when the screen resumes from the WebView so the fetch
     * re-runs with the freshly-minted cookies, mirroring the legacy auto-retry-on-dismiss.
     */
    data class SolveCloudflareChallenge(val url: String, val api: String) : DetailsEffect
}
