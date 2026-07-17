package me.manga.kira.domain.usecase.sources

import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.repository.SourceAccessRepository
import me.manga.kira.domain.repository.SourcesRepository

/**
 * Toggle a single content source's enabled state.
 *
 * Phase 7.x.sources rework. The rework `SourcesViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user flips a per-source `Switch`. Fire-and-forget: the
 * upstream
 * [me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase] flow re-emits with the
 * source's `isEnabled` flipped once the Room transaction commits — the row's `Switch` reflects
 * the new state by virtue of state-driven rebinding.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [SourcesRepository.setSourceEnabled]". The
 * legacy facade method is `enableDisAbleSource(name, enabled)` — naming-typo and all; the
 * rework interface renames cleanly while the rework `:data` impl forwards verbatim.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as the other
 * mutator use cases across the rework (`MarkUpdateAsReadUseCase` /
 * `DeleteHistoryEntryUseCase`) — the VM depends on a stable use case interface, not on a
 * repository method (DIP); future composition (e.g., propagate the toggle to a sync server, or
 * emit an analytics event, or block-disabling the last-enabled source) lives here, not in the
 * VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `sourcesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster118.staleKdocSweep.cascade,
 * Task #574, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-eighth sibling of the cluster57-117 sweep — wave-18
 * `:domain/usecase/sources/` batch alongside ObserveSourcesUseCase.kt
 * plus SetLanguageEnabledUseCase.kt plus EnableDefaultLanguageSourcesUse-
 * Case.kt):
 *  (a) "Phase 7.x.sources rework — the rework SourcesViewModel injects
 *  this use case and invokes it from `viewModelScope.launch` when the
 *  user flips a per-source Switch; fire-and-forget — the upstream
 *  ObserveSourcesUseCase flow re-emits with the source's isEnabled
 *  flipped once the Room transaction commits — the row's Switch reflects
 *  the new state by virtue of state-driven rebinding" — LIVE-NOT-STALE.
 *  SourcesViewModel.kt L135 primary constructor binds `private val
 *  setSourceEnabled: SetSourceEnabledUseCase`; L153-155 `SourcesIntent.
 *  OnToggleSource rename-to viewModelScope.launch { setSourceEnabled(
 *  intent.source.api, intent.enabled) }` realization confirms the fire-
 *  and-forget posture; cluster108 sibling sweep (Task #564) verified the
 *  SourcesIntent.OnToggleSource KDoc at SourcesIntent.kt:103.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to SourcesRepository.
 *  setSourceEnabled; the legacy facade method is enableDisAbleSource(
 *  name, enabled) — naming-typo and all; the rework interface renames
 *  cleanly while the rework `:data` impl forwards verbatim" — LIVE-NOT-
 *  STALE. L32-33 realization `repository.setSourceEnabled(api, enabled)`
 *  single-line pass-through; SourcesRepositoryImpl `:data` impl verified
 *  at cluster25 sibling sweep (Task #481) — the legacy enableDisAble-
 *  Source typo-named facade is forwarded verbatim under the clean
 *  rework signature.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  same rationale as the other mutator use cases (MarkUpdateAsReadUse-
 *  Case / DeleteHistoryEntryUseCase) — the VM depends on a stable use
 *  case interface, not on a repository method (DIP)" — LIVE-NOT-STALE.
 *  Peer mutator-DIP rationale cross-refs all SWEPT: MarkUpdateAsReadUse-
 *  Case (cluster16 Task #472), DeleteHistoryEntryUseCase (cluster112
 *  Task #568); plus the wave-18 sibling SetLanguageEnabledUseCase (this
 *  cluster). The peer-fire-and-forget-mutator posture holds across the
 *  wave-cadence cascade.
 *  (d) "Future composition (propagate the toggle to a sync server, emit
 *  an analytics event, block-disabling the last-enabled source) lives
 *  here, not in the VM" — FORECAST-NOT-YET-FULFILLED. Recursive search
 *  for sync-server propagation, analytics emit, plus last-enabled-block
 *  guard returns zero matches; the use case remains the single-line
 *  pass-through shape. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  `factory` in `sourcesReworkModule` (factory: stateless, cheap to
 *  construct, never shared)" — LIVE-NOT-STALE. SourcesReworkModule.kt
 *  L109 `factory { SetSourceEnabledUseCase(get()) }` realization
 *  confirms factory lifecycle.
 *  Five classifications STAND on their own merits as a faithful Set-
 *  SourceEnabledUseCase manifest. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetSourceEnabledUseCase(
    private val repository: SourcesRepository,
    private val sourceAccessRepository: SourceAccessRepository,
) {
    suspend operator fun invoke(api: String, enabled: Boolean): Boolean {
        if (sourceAccessRepository.state.value != SourceAccessState.ACTIVATED) return false
        repository.setSourceEnabled(api, enabled)
        return true
    }
}
