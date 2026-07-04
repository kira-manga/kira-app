package me.manga.kira.presentation.whatsnew

import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.presentation.mvi.MviState

/**
 * Immutable view-state for the rework What's New screen.
 *
 * Phase 7.x.whatsnew. Carries the three fields the foundation `:ui` composable renders:
 *
 * - [isLoading] — `true` between [WhatsNewViewModel.init]'s feature-fetch launch and the
 *   first emission. Drives a centered `CircularProgressIndicator` in [WhatsNewScreen] for
 *   the network-load window (the legacy uses the same posture — see `WhatsNewState.IsLoading`
 *   in the legacy `:shared`). On retry the flag flips back to `true` until the re-fetch
 *   resolves; same posture as the Reader's chapter-load placeholder.
 * - [features] — the resolved feature list. Empty list IS a valid terminal state: either
 *   the remote endpoint returned no features for the running version OR the remote fetch
 *   failed and the fallback `getDefaultFeatures()` returned its current empty list (TODO at
 *   the `:shared` level — Phase 10's content-population lift). [WhatsNewScreen] branches:
 *   non-empty → renders the LazyColumn; empty + `!isLoading` → renders the "No features"
 *   placeholder. Same empty-vs-loaded shape as
 *   [me.manga.kira.presentation.statistics.StatisticsState.entries].
 * - [errorMessage] — non-null when the remote fetch failed (legacy parity for the
 *   `WhatsNewState.Error` variant). The foundation `:ui` renders it as a centered Text +
 *   Retry button. The string is the [Throwable.message] forwarded verbatim (legacy parity);
 *   localization is deferred to Phase 10. Null on success OR on the empty-but-no-error path.
 *
 * **Why a single state, not a sealed `Loading`/`Loaded`/`Error` ADT (like the legacy
 * `WhatsNewState` does)** — same posture as
 * [me.manga.kira.presentation.about.AboutState] / [me.manga.kira.presentation.statistics.StatisticsState].
 * The three "modes" are mutually-overlap-able states of a single data class (loading-with-
 * stale-features-still-visible during retry, loaded-with-no-features-and-no-error, etc.).
 * The legacy sealed-state forced the VM to ALSO maintain a separate `selectedTabIndex` state
 * field outside the sealed wrapper, defeating the purpose of the discriminator. Flat data
 * class is simpler and the :ui's `when` collapses to three boolean reads.
 *
 * **Pager index now modelled (Phase 7.x.whatsnew.pager)** — [currentPage] mirrors the
 * legacy `WhatsNewState.currentPage: Int` field. Owned by the VM so the state survives
 * Compose recompositions / config changes (in addition to the `rememberPagerState`'s own
 * `rememberSaveable`-backed survival). The `:ui` composable seeds `rememberPagerState` from
 * this value on first composition AND collects pagerState.currentPage to dispatch
 * [WhatsNewIntent.OnPageChanged] back into the VM on user swipe — bi-directional sync, same
 * shape as the legacy. Default `0` (first page on cold start). Strict-MVI OCP §6 — additive
 * change to the data class; foundation call sites that don't read [currentPage] are
 * unaffected.
 *
 * **Fullscreen-viewer state NOT modelled in foundation** — the legacy carries
 * `isFullscreenViewerOpen: Boolean` + `currentMediaItems: List<MediaItem>` +
 * `currentMediaIndex: Int`. All three defer to `Phase 7.x.whatsnew.fullscreen` once the
 * `:platform` MediaPlayer SPI lands; intent surface gains `OnOpenMedia(...)` /
 * `OnDismissMedia` then.
 *
 * **Strict-MVI Contract §17**: pure value type, immutable, no banned features. The
 * [features] `List<WhatsNewFeature>` is a `:domain` value collection — no `:data` /
 * `:shared` leakage. `errorMessage: String?` is the only nullable; the others have
 * sensible defaults.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster31.staleKdocSweep.cascade,
 * Task #487, 2026-05-28): five fulfilled-forecast / stale citations
 * appear in this file, all referencing the retired legacy `:shared`
 * `WhatsNewState` sealed class:
 *  - Lines 13-14 ("see `WhatsNewState.IsLoading` in the legacy
 *    `:shared`").
 *  - Line 24 ("`WhatsNewState.Error` variant").
 *  - Lines 28-29 ("the legacy `WhatsNewState` does").
 *  - Lines 37-38 ("legacy `WhatsNewState.currentPage: Int` field").
 *  - Lines 47-51 ("the legacy carries `isFullscreenViewerOpen:
 *    Boolean` + `currentMediaItems` + `currentMediaIndex`").
 *  All five classified as STALE-SYMBOL-REFERENCE — Phase 9.x.
 *  whatsnew.legacyui.retire (§351) DELETED the legacy `:shared`
 *  What's-new chain (including the cited legacy `WhatsNewState`
 *  sealed class along with its `IsLoading` / `Error` / `Success`
 *  variants and the `currentPage` + `isFullscreenViewerOpen` +
 *  `currentMedia*` fields documented inline). A recursive search
 *  of the `:shared` whatsnew folder for a sealed `WhatsNewState`
 *  class returns NO MATCHES. HOWEVER — the rework [WhatsNewState]
 *  data class (LIVE — this very file) is the canonical rework
 *  What's-new screen state ADT, and three architectural rationales
 *  STAND on their own merits past the §351 fulfilled landing:
 *  (a) single flat data class vs sealed ADT preserves cross-axis
 *  flexibility (loading-with-stale-features-still-visible during
 *  retry); (b) VM-owned `currentPage` survives recomposition / config
 *  changes alongside the `rememberSaveable`-backed pagerState;
 *  (c) fullscreen-viewer deferred to `Phase 7.x.whatsnew.fullscreen`
 *  pending the `:platform` MediaPlayer SPI landing — the
 *  `OnOpenMedia` / `OnDismissMedia` intent surface remains a LIVE
 *  forecast (NOT YET fulfilled — the SPI has not landed). The
 *  [WhatsNewState] data class remains LIVE as the canonical
 *  WhatsNew-screen state ADT consumed by [WhatsNewViewModel] +
 *  the rework `:ui` `WhatsNewScreen`. Original §253-era prose
 *  preserved verbatim per the audit-trail-preservation convention
 *  — the citations are historical record of the design lineage
 *  including the sealed-vs-flat rationale that was subsequently
 *  fulfilled (legacy sealed WhatsNewState retired) across §351;
 *  the deferred fullscreen forecast stays LIVE pending its
 *  follow-on `:platform` SPI slice.
 */
data class WhatsNewState(
    val isLoading: Boolean = true,
    val features: List<WhatsNewFeature> = emptyList(),
    val errorMessage: String? = null,
    val currentPage: Int = 0,
) : MviState
