package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library display-toggle bundle as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeDisplay]".
 * Mirrors [ObserveLibrarySortUseCase] / [ObserveLibrarySortDirectionUseCase] /
 * [ObserveLibraryFilterUseCase] / [ObserveLibraryGridDensityUseCase] /
 * [ObserveLibraryCategoryUseCase] — same one-line delegate shape that gives the VM a narrow,
 * intent-specific dependency rather than a wide repository handle.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.display]. `applyView` is NOT re-run
 * because toggle flips only change which UI surfaces are visible (the `:ui` recomposes the
 * gated branches on `state.display` change). Persistence parity with the §154 filter /
 * §157 density / §159 category persistence slices — same shape, just a sixth pref-cell on
 * the same repository, modelled as a bundle ADT instead of five independent flows (see
 * [LibraryPrefsRepository.observeDisplay] KDoc for the bundle vs. five-flows rationale).
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16a (display-toggle persistence foundation).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster130.staleKdocSweep.cascade,
 * Task #586, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-third sibling of the cluster57-129 sweep
 * — fourth and closing file of the wave-23 `:domain/usecase/library/`
 * 4-file category plus lastUpdated plus display-observer batch alongside
 * ObserveLibraryCategory plus SetLibraryCategory plus ObserveLibraryLast-
 * Updated; closes cluster130):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeDisplay
 *  + mirrors-ObserveLibrarySort-ObserveLibrarySortDirection-ObserveLibrary-
 *  Filter-ObserveLibraryGridDensity-ObserveLibraryCategory-same-one-line-
 *  delegate-shape-narrow-intent-specific-dependency" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. LibraryViewModel.kt L27 import, L105 ctor
 *  `private val observeLibraryDisplay: ObserveLibraryDisplayUseCase`,
 *  L230 realization `observeLibraryDisplay().onEach { display ->
 *  updateState { it.copy(display = display) } }.launchIn(viewModelScope)`
 *  inside VM init {}. The seven-axis-mirror chain (sort, sortDirection,
 *  filter, gridDensity, category, lastUpdated, display) is preserved
 *  verbatim across the wave-23 cycle. VM L224-229 KDoc reference
 *  preserved verbatim: "§150 rung 16b (Task #334): display-toggle bundle
 *  collector — projects the persisted five-flag snapshot into
 *  state.display. No applyView re-run".
 *  (b) "applyView-NOT-re-run-because-toggle-flips-only-change-which-ui-
 *  surfaces-are-visible + :ui-recomposes-gated-branches-on-state.display-
 *  change + bundle-ADT-instead-of-five-independent-flows + see-Library-
 *  PrefsRepository.observeDisplay-KDoc-for-bundle-vs-five-flows-
 *  rationale" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The deliberate
 *  asymmetry vs the sort/filter/category observers (which re-apply
 *  applyView) stands — display is a layout-only axis. VM L230 collector
 *  projects to state.display without invoking applyView, matching the
 *  prose verbatim. Cross-ref to ObserveLibraryGridDensity + ObserveLibrary-
 *  LastUpdated (98th + 102nd siblings) confirms the status/layout-only-
 *  no-applyView posture is now shared by 3 observers (density +
 *  lastUpdated + display).
 *  (c) "Persistence parity with §154 filter / §157 density / §159
 *  category persistence slices — same shape, just a sixth pref-cell on
 *  the same repository, modelled as a bundle ADT instead of five
 *  independent flows + §150 ladder rung 16a + §6 DIP + Koin factory
 *  binding" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryReworkModule.kt
 *  L13 import, L139 `factory { ObserveLibraryDisplayUseCase(get()) }`
 *  realization. The §150 rung 16a + 16b slices (Task #333 + #334,
 *  completed) are the canonical introducers; same-repo-sixth-axis-bundle-
 *  ADT framing stands. Closes cluster130; remaining 7 library/ files
 *  split across cluster131 (5 display setters: SetLibraryShowButtons +
 *  SetLibraryShowCount + SetLibraryShowDetails + SetLibraryShowSource +
 *  SetLibraryShowTabs) + cluster132 (2 toggle pair: ToggleMangaLiked +
 *  ToggleMangaWatchingNow). Three classifications STAND on their own
 *  merits. Original Phase 7.x.library.display.foundation-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveLibraryDisplayUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<LibraryDisplay> = repository.observeDisplay()
}
