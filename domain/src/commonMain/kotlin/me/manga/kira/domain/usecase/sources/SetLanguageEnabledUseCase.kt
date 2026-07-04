package me.manga.kira.domain.usecase.sources

import me.manga.kira.domain.repository.SourcesRepository

/**
 * Toggle every content source in a given language together.
 *
 * Phase 7.x.sources rework. The rework `SourcesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user flips a per-language `Switch` in the language
 * group header. Fire-and-forget: the upstream
 * [me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase] flow re-emits once each
 * per-source Room write commits — every row in the language group reflects the new state by
 * virtue of state-driven rebinding.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [SourcesRepository.setLanguageEnabled]". The
 * per-language fan-out (snapshot, filter, forward-per-source) lives inside the rework `:data`
 * impl rather than the VM — the VM stays free of repository-shape leakage.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [SetSourceEnabledUseCase] — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., a confirmation dialog before
 * bulk-disabling a language group, or restricting to currently-WORKING sources) lives here,
 * not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `sourcesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster118.staleKdocSweep.cascade,
 * Task #574, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-eighth sibling of the cluster57-117 sweep — wave-18
 * `:domain/usecase/sources/` batch alongside ObserveSourcesUseCase.kt
 * plus SetSourceEnabledUseCase.kt plus EnableDefaultLanguageSourcesUse-
 * Case.kt):
 *  (a) "Phase 7.x.sources rework — the rework SourcesViewModel injects
 *  this use case and invokes it from `viewModelScope.launch` when the
 *  user flips a per-language Switch in the language group header; fire-
 *  and-forget — the upstream ObserveSourcesUseCase flow re-emits once
 *  each per-source Room write commits — every row in the language group
 *  reflects the new state by virtue of state-driven rebinding" — LIVE-
 *  NOT-STALE. SourcesViewModel.kt L136 primary constructor binds
 *  `private val setLanguageEnabled: SetLanguageEnabledUseCase`; L156-158
 *  `SourcesIntent.OnToggleLanguage rename-to viewModelScope.launch {
 *  setLanguageEnabled(intent.language, intent.enabled) }` realization
 *  confirms the fire-and-forget posture; cluster108 sibling sweep (Task
 *  #564) verified the SourcesIntent.OnToggleLanguage KDoc at Sources-
 *  Intent.kt:115.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to SourcesRepository.
 *  setLanguageEnabled; the per-language fan-out (snapshot, filter,
 *  forward-per-source) lives inside the rework `:data` impl rather than
 *  the VM — the VM stays free of repository-shape leakage" — LIVE-NOT-
 *  STALE. L31-32 realization `repository.setLanguageEnabled(language,
 *  enabled)` single-line pass-through; SourcesRepositoryImpl `:data`
 *  impl per-language fan-out verified at cluster25 sibling sweep (Task
 *  #481) — the snapshot-filter-forward-per-source mechanism is `:data`-
 *  internal; the VM sees only the suspend pass-through signature.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  same rationale as SetSourceEnabledUseCase — the VM depends on a
 *  stable use case interface, not on a repository method (DIP)" — LIVE-
 *  NOT-STALE. Peer mutator-DIP rationale cross-ref to the wave-18
 *  sibling SetSourceEnabledUseCase (this cluster) plus the broader peer
 *  cohort MarkUpdateAsReadUseCase (cluster16 Task #472) plus Delete-
 *  HistoryEntryUseCase (cluster112 Task #568).
 *  (d) "Future composition (a confirmation dialog before bulk-disabling
 *  a language group, restricting to currently-WORKING sources) lives
 *  here, not in the VM" — FORECAST-NOT-YET-FULFILLED. Recursive search
 *  for confirmation-dialog-before-bulk-disable plus WORKING-source-only
 *  restriction returns zero matches; the use case remains the single-
 *  line pass-through shape. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  `factory` in `sourcesReworkModule` (factory: stateless, cheap to
 *  construct, never shared)" — LIVE-NOT-STALE. SourcesReworkModule.kt
 *  L110 `factory { SetLanguageEnabledUseCase(get()) }` realization
 *  confirms factory lifecycle.
 *  Five classifications STAND on their own merits as a faithful Set-
 *  LanguageEnabledUseCase manifest. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetLanguageEnabledUseCase(
    private val repository: SourcesRepository,
) {
    suspend operator fun invoke(language: String, enabled: Boolean) =
        repository.setLanguageEnabled(language, enabled)
}
