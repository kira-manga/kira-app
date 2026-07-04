package me.manga.kira.domain.usecase.whatsnew

import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.repository.WhatsNewRepository

/**
 * Resolve the list of What's New features for the rework WhatsNew screen.
 *
 * Phase 7.x.whatsnew (foundation). The rework `WhatsNewViewModel` injects this use case and
 * invokes it once from `viewModelScope.launch` in its `init {}` block — the result populates
 * `state.value.features`. The `WhatsNewIntent.OnRetry` handler re-launches the same use case
 * call to re-fetch.
 *
 * Suspend pass-through; no combining, no projection, no error mapping. The repository contract
 * surfaces an empty list (NOT a `Result.failure`) on any remote failure — the empty-list signal
 * is the foundation slice's "nothing to show" UX (renders the "No new features in this version"
 * placeholder in the `:ui`).
 *
 * **Why a use case at all when this is a single-line pass-through**: same rationale as the
 * other rework one-shot use cases (`GetAppMetadataUseCase` from Phase 7.x.about,
 * `IsAdultContentUseCase` from Phase 6.3.4, `GetReadingProgressUseCase` from
 * Phase 7.x.reader.resumeposition) — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., merge a cached local list with the remote
 * list, decorate with an analytics event on first-read) lives here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `whatsNewReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster111.staleKdocSweep.cascade,
 * Task #567, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-first sibling of the cluster57-110 sweep — opens the
 * wave-11 `:domain/usecase/whatsnew/` batch alongside MarkWhatsNewSeen-
 * UseCase.kt):
 *  (a) "Phase 7.x.whatsnew foundation — rework WhatsNewViewModel injects
 *  this use case plus invokes it once from viewModelScope.launch in its
 *  init {} block; the result populates state.value.features" — LIVE-NOT-
 *  STALE. WhatsNewViewModel.kt L147-149 init block calls loadFeatures();
 *  L159-170 loadFeatures() wraps the suspend `getWhatsNewFeatures()` call
 *  in viewModelScope.launch{} plus realizes `updateState { it.copy(
 *  isLoading = false, features = features) }` post-resolution. Init-
 *  launch posture verified at cluster109 sibling sweep (Task #565).
 *  (b) "WhatsNewIntent.OnRetry handler re-launches the same use case call
 *  to re-fetch" — LIVE-NOT-STALE. WhatsNewViewModel.kt L153 `WhatsNew-
 *  Intent.OnRetry rename-to loadFeatures()` realizes the re-launch via
 *  the shared private helper; cluster109 sibling sweep verified the
 *  OnRetry re-launch posture preserves the legacy WhatsNewViewModel.
 *  retryLoadFeatures() semantics verbatim.
 *  (c) "Suspend pass-through; no combining, no projection, no error
 *  mapping; repository contract surfaces an empty list (NOT a Result.
 *  failure) on any remote failure — empty-list signal is the foundation
 *  slice's `nothing to show` UX (renders the `No new features in this
 *  version` placeholder in the `:ui`)" — LIVE-NOT-STALE. L32 realization
 *  `repository.getFeatures()` single-line pass-through; WhatsNewReposi-
 *  tory.kt L23 KDoc confirms `suspend fun getFeatures()` returns
 *  List<WhatsNewFeature> (not Result-wrapped); WhatsNewRepositoryImpl
 *  empty-list-on-failure realization verified at cluster25 sibling sweep
 *  (Task #481).
 *  (d) "Future composition — merge a cached local list with the remote
 *  list, decorate with an analytics event on first-read" — FORECAST-NOT-
 *  YET-FULFILLED. Recursive search for local-cache-merge / analytics-
 *  emission decoration on this use case returns zero matches; the use
 *  case remains a single-line pass-through. Forecast posture preserved
 *  verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `whatsNewReworkModule`" — LIVE-NOT-STALE. WhatsNewRework-
 *  Module.kt L102 `factory { GetWhatsNewFeaturesUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Get-
 *  WhatsNewFeaturesUseCase manifest. Original Phase 7.x.whatsnew-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
class GetWhatsNewFeaturesUseCase(
    private val repository: WhatsNewRepository,
) {
    suspend operator fun invoke(): List<WhatsNewFeature> = repository.getFeatures()
}
