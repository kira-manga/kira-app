package me.manga.kira.presentation.whatsnew

import me.manga.kira.presentation.mvi.MviIntent

/**
 * Sealed Intent hierarchy for the rework What's New screen.
 *
 * Phase 7.x.whatsnew. Four variants ([OnRetry], [OnMarkSeen], [OnPageChanged], [OnOpenVideo]),
 * the first two matching the legacy `WhatsNewViewModel`'s user-driven actions:
 *
 * - [OnRetry] — user tapped the "Retry" button on the error placeholder. The VM re-runs
 *   [me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase], flipping
 *   [WhatsNewState.isLoading] to `true` and clearing [WhatsNewState.errorMessage] before
 *   the re-fetch. Same posture as the legacy `WhatsNewViewModel.retryLoadFeatures()`.
 * - [OnMarkSeen] — user finished viewing the screen. The rework `WhatsNewScreen` submits this
 *   from the loaded surface's dismiss paths — the header X close and the last-page "Get Started"
 *   button (ui/.../whatsnew/WhatsNewScreen.kt:253 and :286), both immediately before
 *   `onGetStarted()`. The VM fires
 *   [me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase] on `viewModelScope`. The
 *   error/empty surfaces only dismiss; they do NOT mark seen (native reserves mark-seen for the
 *   loaded screen's dismiss/Get-Started).
 *
 * **Phase 7.x.whatsnew.pager adds [OnPageChanged]** — the `:ui` composable now renders a
 * `HorizontalPager` instead of a flat LazyColumn. The pager's `currentPage` is mirrored back
 * into [WhatsNewState.currentPage] via this intent (the `:ui` uses a `LaunchedEffect` keyed
 * on `pagerState.currentPage` to dispatch it on user swipe). Bi-directional sync — the
 * `:ui` seeds `rememberPagerState(initialPage = state.currentPage, ...)`. Strict-MVI OCP §6
 * grown additively; the foundation's existing `OnRetry` / `OnMarkSeen` arms are untouched.
 *
 * **Why no `OnOpenMedia(...)` / `OnDismissMedia` intents in foundation** — the legacy
 * FullscreenMediaViewer is deferred to `Phase 7.x.whatsnew.fullscreen`. Same OCP rationale.
 *
 * **Why `OnMarkSeen` is `data object` not `data class` carrying a version** — the VM reads
 * the current version from `AppVersionProvider` at the moment the use case fires (the
 * repository contract handles it), NOT from a caller-supplied param. This keeps the :ui
 * caller decoupled from the legacy version-string contract. Same pattern as the
 * [me.manga.kira.domain.repository.WhatsNewRepository.markSeen] no-param contract.
 *
 * **Contract §6 OCP**: sealed interface — adding pager/fullscreen/should-show intents in
 * follow-on sub-slices doesn't modify existing variants. The VM's `when (intent)` block
 * grows one arm per sub-slice; nothing else changes.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster109.staleKdocSweep.cascade,
 * Task #565, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-ninth sibling of the cluster57-108 sweep — opens
 * the wave-9 `:presentation/whatsnew/` batch alongside WhatsNewEffect.kt
 * plus WhatsNewViewModel.kt):
 *  (a) "Phase 7.x.whatsnew foundation — two variants OnRetry plus
 *  OnMarkSeen matching legacy WhatsNewViewModel's two user-driven
 *  actions; Phase 7.x.whatsnew.pager extension adds OnPageChanged" —
 *  NOW understated. The sealed interface declares FOUR variants: OnRetry
 *  data object, OnMarkSeen data object, OnPageChanged data class
 *  (`index: Int`, Phase 7.x.whatsnew.pager), and OnOpenVideo data class
 *  (`url: String`, GAP-WN-01 Phase 7.x.whatsnew.media). Foundation-to-
 *  pager-to-media append posture preserved — no rewrites to the
 *  foundation variants when later slices landed.
 *  (b) "Auto-trigger lives in Phase 7.x.whatsnew.gate (deferred) —
 *  intent wired now to keep the MVI surface complete without forcing a
 *  contract bump when gate lands" — NOW STALE for the no-submit claim.
 *  The `:ui` `WhatsNewScreen` DOES submit OnMarkSeen today, from the
 *  loaded surface's dismiss paths (ui/.../whatsnew/WhatsNewScreen.kt:253
 *  header X close, :286 last-page Get-Started). A separate About-screen
 *  should-show auto-trigger (Phase 7.x.whatsnew.gate) remains a forecast.
 *  (c) "Why no `OnOpenMedia(...)` / `OnDismissMedia` intents in
 *  foundation — FullscreenMediaViewer deferred to Phase 7.x.whatsnew.
 *  fullscreen" — FORECAST-NOT-YET-FULFILLED. The `:platform` Media-
 *  Player SPI has not landed; the corresponding intent variants remain
 *  unbuilt. Peer cross-ref to WhatsNewEffect.kt forecast (a sibling at
 *  this cluster109 sweep) — same fullscreen-slice forecast lineage
 *  preserved.
 *  (d) "Why OnMarkSeen is data object not data class carrying a version
 *  — VM reads current version from AppVersionProvider via the use case;
 *  legacy WhatsNewRepository.markSeen no-param contract" — LIVE-NOT-
 *  STALE. L49 `data object OnMarkSeen` declaration carries zero payload;
 *  WhatsNewViewModel.kt L88 `WhatsNewIntent.OnMarkSeen rename-to mark-
 *  WhatsNewSeen()` confirms the suspend use case call carries no params
 *  from the intent surface.
 *  (e) "Bi-directional pager sync — `:ui` seeds rememberPagerState
 *  (initialPage = state.currentPage) plus dispatches OnPageChanged on
 *  user swipe via LaunchedEffect" — LIVE-NOT-STALE. L56 OnPageChanged
 *  carrying `val index: Int`; WhatsNewViewModel.kt L89 handler realizes
 *  the mirror: `updateState { it.copy(currentPage = intent.index) }`.
 *  (f) "Contract §6 OCP — sealed interface, adding pager/fullscreen/
 *  should-show intents in follow-on sub-slices doesn't modify existing
 *  variants" — LIVE-NOT-STALE. L43 `sealed interface WhatsNewIntent :
 *  MviIntent`; OCP foundation-to-pager extension lineage realized
 *  without any rewrites to OnRetry or OnMarkSeen.
 *  Six classifications STAND on their own merits as a faithful
 *  WhatsNewIntent surface manifest. Original Phase 7.x.whatsnew-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface WhatsNewIntent : MviIntent {

    /** User tapped Retry on the error placeholder; re-runs the feature fetch. */
    data object OnRetry : WhatsNewIntent

    /**
     * Screen has been seen by the user; persists the mark-seen prefs. Submitted by
     * `WhatsNewScreen` from the loaded surface's dismiss paths (header X close + last-page
     * "Get Started").
     */
    data object OnMarkSeen : WhatsNewIntent

    /**
     * User swiped to a new page in the HorizontalPager. The VM mirrors [index] into
     * [WhatsNewState.currentPage] so the value survives Compose recomposition / config
     * change. Added by Phase 7.x.whatsnew.pager.
     */
    data class OnPageChanged(val index: Int) : WhatsNewIntent

    /**
     * User tapped a feature card's video poster (GAP-WN-01, Phase 7.x.whatsnew.media). The VM
     * emits [WhatsNewEffect.OpenVideo] with [url] verbatim; the route adapter opens it via the
     * platform `IntentLauncher`. DEVIATION(platform) substitute for the native inline
     * `VideoView` player — the rework `:ui` has no cross-platform inline player and adds no new
     * video dependency. Same pass-through posture as
     * [me.manga.kira.presentation.about.AboutIntent.OnOpenUrl].
     */
    data class OnOpenVideo(val url: String) : WhatsNewIntent
}
