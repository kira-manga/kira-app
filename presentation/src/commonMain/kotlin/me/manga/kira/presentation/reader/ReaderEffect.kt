package me.manga.kira.presentation.reader

import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [ReaderViewModel] for the view to perform once and forget.
 *
 * Strict MVI: effects carry only the trigger (navigation target, typed error). Recurrent UI
 * state (the page list, error banners, the loading spinner) lives in [ReaderState] so
 * configuration changes don't replay them.
 *
 * Surface scope (Phase 6.4.3): mirrors the trimmed [ReaderIntent] surface — back navigation
 * and error toasts only. Future slices land their own effects (e.g. `NavigateToChapter(next)`
 * once multi-chapter navigation arrives) without changing existing ones.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster101.staleKdocSweep.cascade,
 * Task #557, 2026-05-28): the 2-claim sealed-effect manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-first sibling of the cluster57-100 sweep — opens
 * the wave-7 `:presentation/reader/` batch):
 *  (a) "Surface scope (Phase 6.4.3): mirrors the trimmed [ReaderIntent]
 *  surface — back navigation and error toasts only" — STALE-SUPERSEDED.
 *  The L45 `OpenChapterInWebView(url, api)` variant LIVE alongside
 *  `NavigateBack` (L20) plus `ShowError(error)` (L23) — three effects
 *  enumerated, not two. Phase 7.x.reader.modelayout.openwebview added
 *  the third variant. The "back navigation and error toasts only"
 *  snapshot is the Phase 6.4.3-era surface, accurately preserved as
 *  audit trail but no longer matches the live sealed hierarchy.
 *  (b) "Future slices land their own effects (e.g. `NavigateToChapter(
 *  next)` once multi-chapter navigation arrives)" — REROUTED-OUT-OF-
 *  SURFACE. Multi-chapter navigation DID land (Phase 7.x.reader.next —
 *  `OnNextChapter` at ReaderIntent.kt:92 plus `OnPrevChapter` at L100
 *  LIVE), but the chosen implementation strategy is in-place navigation:
 *  the reducer routes Next / Prev through `onEnter` recursion which
 *  reuses the existing page-fetch path. No `NavigateToChapter` effect
 *  was required because the navigation is intra-screen, not inter-
 *  screen — see `ReaderIntent.OnNextChapter` KDoc at L78-91 ("No new
 *  nav destination; no `ReaderEffect`"). The forecast's specific shape
 *  was superseded by a cleaner strategy that didn't need a new effect.
 *  Plus a third unforecasted variant: the `OpenChapterInWebView` KDoc
 *  at L25-44 STANDS as LIVE-NOT-STALE on its own merits (legacy
 *  `ImageLoadError` parity citation plus §71 cross-ref both verified
 *  against composeApp/.../reader/ui/components/ImageLoadError.kt).
 *  One STALE-SUPERSEDED classification plus one REROUTED-OUT-OF-
 *  SURFACE classification STAND on their own merits as a faithful
 *  Reader-effect-surface manifest. Original Phase 6.4.3-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface ReaderEffect : MviEffect {

    /** View should pop the Reader screen off the navigation stack. */
    data object NavigateBack : ReaderEffect

    /** View should show a non-blocking error toast / snackbar. */
    data class ShowError(val error: AppError) : ReaderEffect

    /**
     * View should open the chapter's source URL in the in-app WebView (Phase 7.x.reader.modelayout.openwebview).
     *
     * Legacy parity: legacy `ImageLoadError` (composeApp/.../reader/ui/components/ImageLoadError.kt)
     * exposes a Retry button + Open-in-WebView button pair. The retry half shipped in §71 as
     * a Coil-level UI-only restart; the Open-in-WebView half ships here as a proper MVI
     * effect because it requires nav-host coordination — the legacy route navigates to
     * `Screen.WebView(url, api)` via `navController.safeNavigate(...)` and the rework
     * reuses the same nav target (the in-app WebView screen is shared infrastructure
     * during the strangler-fig migration; route-swap reconciles this later in Phase 9.x).
     *
     * Carries two fields:
     *  - [url]: the chapter source URL (the rework `Chapter.url`).
     *  - [api]: the source name (the rework `Manga.api`). Needed by `Screen.WebView` so the
     *    in-app browser can apply source-specific cookies / headers.
     *
     * Effect rather than direct nav: the VM doesn't own a `NavController`. The screen
     * consumes the effect and forwards it to a callback that the route adapter binds to the
     * actual `safeNavigate(Screen.WebView(...))` call.
     */
    data class OpenChapterInWebView(val url: String, val api: String) : ReaderEffect

    /**
     * View should capture the current page and hand it to the platform share sheet (Reader parity
     * item #5).
     *
     * The VM owns no capture machinery (capturing a rendered frame is a Compose `:ui` concern and
     * encoding it is a `:platform` concern), so this effect carries no payload — it is a pure
     * "go capture + share now" trigger. The `:ui` reader screen consumes it, records the page area
     * into a Compose `GraphicsLayer`, decodes to an `ImageBitmap`, and forwards the bitmap to a
     * route-adapter callback. The route adapter encodes the bitmap to PNG bytes and invokes the
     * existing [me.manga.kira.platform.image.ScreenshotProvider.shareBitmapBytes] SPI.
     *
     * Effect rather than direct call: same rationale as [OpenChapterInWebView] — the VM doesn't own
     * the `ScreenshotProvider` (a `:composeApp`-resolved `:platform` singleton) and `:ui` stays free
     * of `:data` / `:platform` types. Mirrors the legacy reader's Share IconButton →
     * `ScreenshotUtils.captureAndShare(...)` behaviour.
     */
    data object ShareCurrentPage : ReaderEffect

    /**
     * Fetch of a page failed with an HTTP 403 — almost always a Cloudflare / anti-bot interstitial
     * that the source serves until the user solves a browser challenge (which sets the session
     * cookies the source then expects on its image requests). Reader parity item #6.
     *
     * Legacy parity: the legacy reader AUTO-triggered the WebView interstitial when a page load
     * returned 403, then reloaded the chapter once the challenge cleared. The rework dropped that
     * auto path during migration — a Cloudflare-protected source's page just showed "failed to
     * load image" with a manual "Open in WebView" button. This effect restores the AUTO trigger:
     * the VM emits it (instead of the generic [ShowError] snackbar) when a page-fetch failure is a
     * 403. The `:composeApp` adapter maps it to `Screen.WebView(url, api)`; once the user returns,
     * the adapter auto-re-dispatches [ReaderIntent.OnRetry] so the chapter re-fetches with the
     * freshly-minted cookies. Mirrors the proven Details
     * [me.manga.kira.presentation.details.DetailsEffect.SolveCloudflareChallenge] pattern.
     *
     * Carries the chapter source [url] (the rework `Chapter.url`) and the source [api] (the rework
     * `Manga.api`) — both needed by `Screen.WebView` so the in-app browser can apply source-specific
     * cookies / headers.
     */
    data class SolveCloudflareChallenge(val url: String, val api: String) : ReaderEffect

    /**
     * User tapped the bookmark star on a chapter whose manga is NOT in the library (#15).
     *
     * A `saved_chapters` row exists only once the manga is in the library, so a bookmark toggle on
     * a not-in-library chapter is a silent no-op in the store. Native shows a toast instructing the
     * user to add the manga to the Library first; this effect restores that feedback. Payload-free
     * — it carries only the trigger; the `:ui` layer maps it to its own localized string (the
     * message is a positive instruction, not an [AppError], so [ShowError] does not fit).
     */
    data object ShowNotInLibrary : ReaderEffect
}
