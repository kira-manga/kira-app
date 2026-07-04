package me.manga.kira.presentation.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Reader screen.
 *
 * Sealed so the reducer's `when` is exhaustive (OCP — compile-time enforcement that new
 * actions can't sneak in without the ViewModel handling them).
 *
 * Surface scope: this MVI surface covers the actions the rework `:domain` currently exposes a
 * use case for — page fetching, reading-mode persistence, and view navigation. Bookmark toggle,
 * statistics start/end, and multi-chapter prefetching all live in the legacy `:shared`
 * `ReaderViewModel` today; each is a future slice that lands its own intent (e.g.
 * `OnToggleBookmark`, `OnNextChapter`) at the same time as the use case that backs it. Holding
 * the surface narrow keeps the VM's reducer reviewable end-to-end in one screenful and avoids
 * declaring sealed cases the VM can't yet implement.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster101.staleKdocSweep.cascade,
 * Task #557, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-first sibling of the cluster57-100 sweep —
 * sibling of cluster101 ReaderEffect.kt):
 *  (a) "Surface scope: this MVI surface covers the actions the rework
 *  `:domain` currently exposes a use case for — page fetching, reading-
 *  mode persistence, and view navigation" — STALE-SUPERSEDED. The LIVE
 *  intent surface at L22-164 enumerates ELEVEN intents covering page
 *  fetching (`OnEnter` L33, `OnRetry` L52), reading-mode persistence
 *  (`OnReadingModeChanged` L76), view navigation (`OnBackClick` L55,
 *  `OnUiToggle` L66, `OnPageChanged` L45), multi-chapter navigation
 *  (`OnNextChapter` L92, `OnPrevChapter` L100), statistics bracketing
 *  (`OnScreenResumed` L127, `OnScreenPaused` L145), and WebView nav
 *  (`OnOpenInWebView` L163). The Phase 6.4.3-era narrow trio is the
 *  initial slice scope; eight additional intents have landed since.
 *  (b) "Bookmark toggle, statistics start/end, and multi-chapter
 *  prefetching all live in the legacy `:shared` `ReaderViewModel`
 *  today; each is a future slice that lands its own intent (e.g.
 *  `OnToggleBookmark`, `OnNextChapter`)" — MIXED-with-partial-
 *  fulfillment:
 *    - "statistics start/end ... future slice" — FULFILLED-
 *      PREDICTION. Phase 6.4.x.statistics shipped `OnScreenResumed`
 *      (L127) plus `OnScreenPaused` (L145) backed by
 *      `StartReadingSessionUseCase` plus `EndReadingSessionUseCase`
 *      wired in ReaderViewModel L193-194. Lifecycle-bracketed session
 *      timer LIVE.
 *    - "multi-chapter prefetching ... future slice (e.g. `OnNext-
 *      Chapter`)" — FULFILLED-PREDICTION with naming-strategy
 *      adjustment. Phase 7.x.reader.next shipped `OnNextChapter`
 *      (L92) plus `OnPrevChapter` (L100) under the exact forecast
 *      name; "prefetching" specifically narrowed to "navigation" —
 *      intra-manga in-place navigation via `onEnter` recursion, not
 *      background prefetch of neighbor chapters.
 *    - "bookmark toggle ... future slice (e.g. `OnToggleBookmark`)"
 *      — FORECAST-NOT-YET-FULFILLED. Recursive Grep for
 *      `OnToggleBookmark` across `:presentation` matches ZERO live
 *      references. Task #217 (Phase 6.4.x.bookmark — `Observe-
 *      ChapterBookmarkUseCase` plus `ToggleChapterBookmarkUseCase`)
 *      remains PENDING. Forecast holds as planned-but-not-yet-
 *      landed work.
 *  Plus one unforecasted addition: `OnOpenInWebView` (L163) shipped
 *  in Phase 7.x.reader.modelayout.openwebview as an eleventh intent
 *  not anticipated by this Phase 6.4.3-era file-scope KDoc; it is
 *  documented on its own at L147-163 ("Open in WebView" half of the
 *  legacy `ImageLoadError` button row).
 *  The nine per-intent KDocs at L24-163 (OnEnter, OnPageChanged,
 *  OnRetry, OnBackClick, OnUiToggle, OnReadingModeChanged,
 *  OnNextChapter, OnPrevChapter, OnScreenResumed, OnScreenPaused,
 *  OnOpenInWebView) STAND as LIVE-NOT-STALE on their own merits per
 *  recursive symbol verification against ReaderViewModel.kt's reducer
 *  at L181-196.
 *  One STALE-SUPERSEDED classification plus one MIXED-with-partial-
 *  fulfillment classification (two FULFILLED-PREDICTION plus one
 *  FORECAST-NOT-YET-FULFILLED sub-classifications) STAND on their own
 *  merits as a faithful Reader-intent-surface manifest. Original
 *  Phase 6.4.3-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
sealed interface ReaderIntent : MviIntent {

    /**
     * Screen first becomes visible (or a configuration change re-attached the screen to a
     * fresh host). Carries the [Manga] + [Chapter] identity that this Reader instance renders.
     *
     * Idempotent on re-entry: the reducer drops a subsequent emission whose `(manga, chapter)`
     * identity matches the in-state pair, so a config change does NOT re-trigger the network
     * fetch. The StateFlow re-emission to the new host already carries the previously-fetched
     * pages — same posture as [me.manga.kira.presentation.details.DetailsIntent.OnEnter].
     */
    data class OnEnter(val manga: Manga, val chapter: Chapter) : ReaderIntent

    /**
     * User scrolled / paged to a different page within the current chapter. The reducer
     * stores [pageIndex] in [ReaderState.currentPageIndex] without re-fetching. Out-of-range
     * indices are clamped to the current `pages` list bounds; emissions whose index matches
     * the in-state value are dropped to avoid pointless reducer churn.
     *
     * Beyond the clamp, the reducer persists the active chapter's resume position on every page
     * change and — when the user scrolls across an appended-chapter boundary in the continuous
     * feed — re-points the bookmark observer and records reading history for the now-visible
     * chapter. See [ReaderViewModel.onPageChanged].
     */
    data class OnPageChanged(val pageIndex: Int) : ReaderIntent

    /**
     * User tapped retry after a fetch failure (or any other UI surface that needs to re-run
     * the page fetch — e.g. pull-to-refresh in a future slice). The reducer drops this intent
     * when a fetch is already in flight (re-entrance guard, see [ReaderViewModel.onRetry] KDoc).
     */
    data object OnRetry : ReaderIntent

    /** User tapped the back affordance. View pops via the emitted Effect. */
    data object OnBackClick : ReaderIntent

    /**
     * User tapped the reader page area (not a control). Flips [ReaderState.isUiVisible],
     * which the `:ui` layer uses to show or hide the top bar and the page-indicator HUD.
     *
     * Pure UI-chrome concern — no side effect, no fetch, no persistence. Mirrors the legacy
     * `:shared` reader's tap-to-toggle posture (the legacy screen tracked an in-screen
     * `isUiVisible: Boolean` directly in a `remember`; the rework hoists it into MVI state
     * so the value survives recomposition and configuration changes consistently).
     */
    data object OnUiToggle : ReaderIntent

    /**
     * User picked a new reading mode from the top-bar picker. The VM forwards [mode] to
     * `SetReadingModeUseCase` (no state mutation inline); the persistence write re-emits
     * through the observer wired in VM `init`, which in turn updates
     * [ReaderState.readingMode]. Single source of truth is the on-disk value reflected back
     * — a write rejected by the store (today impossible, future-proof) doesn't desync the
     * UI from disk.
     */
    data class OnReadingModeChanged(val mode: ReadingMode) : ReaderIntent

    /**
     * User tapped the "Next chapter" affordance in the top bar (Phase 7.x.reader.next).
     *
     * In-place navigation: the reducer routes through the same `onEnter` pathway used by the
     * initial entry — replaces the active [Chapter] with `chapters[currentIdx + 1]`, resets
     * `pages` / `currentPageIndex` / `error` / `isUiVisible`, cancels any in-flight page fetch,
     * and starts a new one. No new nav destination; no `ReaderEffect`. The pre-existing chapter
     * list in state is preserved (same manga = same list — no refetch on intra-manga navigation).
     *
     * Re-entrance guard: dropped when `state.isLoading == true` (initial fetch in progress) or
     * when `!state.canGoNext` (at end of list, or chapter not found in list). The `:ui` button
     * disables itself on the same condition; the VM guard closes the gap for any other
     * dispatcher (intent replay, programmatic dispatch).
     */
    data object OnNextChapter : ReaderIntent

    /**
     * User tapped the "Previous chapter" affordance in the top bar (Phase 7.x.reader.next).
     *
     * Mirror of [OnNextChapter] using `chapters[currentIdx - 1]`. Guard condition is
     * `!state.canGoPrev` (at start of list, or chapter not found).
     */
    data object OnPrevChapter : ReaderIntent

    /**
     * The user scrolled to the very end of the current continuous-scroll chapter (#5).
     *
     * APPENDS the next chapter's pages BELOW the current ones in the same scroll list — the current
     * chapter is NOT removed, so the user can freely scroll up/down between them (native parity).
     * Distinct from [OnNextChapter], which CLEARS and jumps to a single chapter (the explicit top-bar
     * / scrubber buttons). The `:ui` dispatches this only for non-paged (WEBTOON / CONTINUOUS_VERTICAL)
     * modes on reach-end; paged modes keep dispatching [OnNextChapter]. Idempotent: dropped while an
     * append is in flight, when the tail chapter is the last one, or when the next is already loaded.
     */
    data object OnAppendNextChapter : ReaderIntent

    /**
     * Screen lifecycle: the host became visible / resumed (Phase 6.4.x.statistics).
     *
     * Dispatched from `ReaderScreenContent`'s `DisposableEffect(Unit)` on first composition (and
     * on re-composition after host restoration). Routes to [StartReadingSessionUseCase] which
     * records `now()` in the session-timer (legacy parity with
     * `ReaderViewModel.onScreenResume()` / `StatisticsRepository.startReadingSession`).
     *
     * Idempotence: a second [OnScreenResumed] before [OnScreenPaused] overwrites the recorded
     * start with the later value — see [ReadingSessionRepository.begin] KDoc. Practically
     * harmless: the only way to trigger this is a buggy double-resume, and the user-visible
     * effect is "the second resume's window is what gets counted", which is the right answer
     * if the first window was actually interrupted by something the VM didn't see.
     *
     * Pure side-effect dispatch (no state mutation). The session timer is intentionally NOT
     * surfaced in [ReaderState] because the UI has no reason to render it — it's a write-only
     * counter that lands in the on-disk statistics totals consumed by the Statistics screen.
     *
     * Why not piggyback on [OnEnter]: OnEnter dispatches once per `(manga, chapter)` identity
     * change (LaunchedEffect keyed on the four-tuple), but a configuration change re-creates
     * the host and re-runs the LaunchedEffect with the same key → no re-dispatch → no
     * re-start of the timer that should have been restarted. The screen-lifecycle intents
     * are keyed on `Unit` via DisposableEffect, so config changes properly bracket every
     * resume / pause cycle.
     */
    data object OnScreenResumed : ReaderIntent

    /**
     * Screen lifecycle: the host left the foreground / is being torn down (Phase 6.4.x.statistics).
     *
     * Dispatched from `ReaderScreenContent`'s `DisposableEffect(Unit).onDispose`. Routes to
     * [EndReadingSessionUseCase] which persists the elapsed minutes (legacy parity with
     * `ReaderViewModel.onScreenPause()` / `StatisticsRepository.endReadingSession`).
     *
     * Safe when no session is in progress (no-op via [ReadingSessionRepository.end] KDoc).
     * This is load-bearing because `DisposableEffect.onDispose` ALWAYS fires (even if the
     * resume callback never landed, e.g. Compose tears the composition down before the host
     * reaches its resumed state) — the no-op guard means that path doesn't corrupt the
     * persisted counter.
     *
     * Sessions shorter than 60 seconds round down to zero minutes and are NOT persisted (legacy
     * parity, lives in the impl).
     */
    data object OnScreenPaused : ReaderIntent

    /**
     * User tapped "Open in WebView" on a page that failed to decode (Phase 7.x.reader.modelayout.openwebview).
     *
     * Legacy parity for the second half of `ImageLoadError`'s button row — the first half
     * (Retry) shipped in §71 as a Coil-level `painter.restart()` (UI-only, no MVI). This
     * half routes nav, so it goes through the MVI surface: the reducer emits
     * [ReaderEffect.OpenChapterInWebView] which the screen forwards to a callback that the
     * route adapter binds to `navController.safeNavigate(Screen.WebView(url, api))`.
     *
     * Carries [url] (the chapter source URL, from `Chapter.url`) and [api] (the source name,
     * from `Manga.api`) because the legacy `Screen.WebView(url, api)` nav target needs both
     * — the source name lets the in-app browser apply source-specific cookies / headers.
     *
     * No state mutation; pure side-effect dispatch. The screen still has the page in error
     * state when the user comes back — that's a Coil cache-policy concern, not an MVI one.
     */
    data class OnOpenInWebView(val url: String, val api: String) : ReaderIntent

    /**
     * User tapped the bookmark affordance in the top bar (Phase 6.4.x.bookmark, Reader-convergence
     * R2). The VM forwards the active chapter URL to `ToggleChapterBookmarkUseCase` (no state
     * mutation inline); the persistence flip re-emits through the per-chapter observe collector
     * wired on chapter establish/change, which updates [ReaderState.isBookmarked]. Single source
     * of truth is the on-disk bookmark column reflected back — deliberately not an optimistic
     * flip. If no current chapter URL is available the intent is ignored.
     */
    data object OnToggleBookmark : ReaderIntent

    /**
     * User tapped the "Share" affordance in the top bar (Reader parity item #5).
     *
     * Legacy parity: the legacy reader's `ControlOverlay` exposed a Share IconButton that called
     * `ScreenshotUtils.captureAndShare(...)` — it hid the chrome, captured the rendered page via
     * `PixelCopy`, wrote a PNG to the cache, and fired an `Intent.ACTION_SEND` chooser. The rework
     * reproduces the same user-visible flow in clean-arch terms: the VM emits
     * [ReaderEffect.ShareCurrentPage]; the `:ui` layer captures the current page area into a
     * Compose `GraphicsLayer`, decodes it to an `ImageBitmap`, and forwards the bitmap to a
     * route-adapter callback that encodes it to PNG bytes and hands them to the existing
     * `:platform` [me.manga.kira.platform.image.ScreenshotProvider.shareBitmapBytes] SPI
     * (which on Android shares via `Intent.ACTION_SEND` through the registered `FileProvider`,
     * on iOS via `UIActivityViewController`, on Desktop by copying the path to the clipboard).
     *
     * Pure side-effect dispatch — no state mutation. Dropped when no pages are loaded yet (nothing
     * to share); the `:ui` button is also hidden / inert in that window.
     */
    data object OnShareCurrentPage : ReaderIntent
}
