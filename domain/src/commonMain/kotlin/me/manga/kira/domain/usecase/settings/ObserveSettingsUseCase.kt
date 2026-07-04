package me.manga.kira.domain.usecase.settings

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.settings.SettingsSnapshot
import me.manga.kira.domain.repository.SettingsRepository

/**
 * Reactive Settings snapshot observer — thin pass-through to [SettingsRepository.observeSettings].
 *
 * Phase 7.x.settings.foundation rework. Pure delegation; no business logic lives here. The
 * mappers + combine logic live in the `:data` impl.
 *
 * Contract §6 SRP: ONE rule — return the [SettingsRepository]'s flow. Future filtering /
 * derivation (e.g., a `derived: Boolean = downloadedOnly && incognito` field) would live in
 * either the snapshot data class or a sibling use case, never here.
 *
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface; consumers (the
 * rework `SettingsViewModel`) depend on this class, never on the impl.
 *
 * Lifecycle: bound as `factory` in `settingsReworkModule` — stateless and cheap to instantiate.
 * No internal state.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster117.staleKdocSweep.cascade,
 * Task #573, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-seventh sibling of the cluster57-116 sweep — opens the
 * wave-17 `:domain/usecase/settings/` batch alongside UpdateSettings-
 * ToggleUseCase.kt plus ClearCacheUseCase.kt, partnering the cluster107
 * sibling sweep of `:presentation/settings/`):
 *  (a) "Phase 7.x.settings.foundation rework — pure delegation; no
 *  business logic lives here; the mappers plus combine logic live in the
 *  `:data` impl" — LIVE-NOT-STALE. SettingsViewModel.kt L144 primary
 *  constructor binds `observeSettings: ObserveSettingsUseCase`; L155
 *  `observeSettings()` invocation in init {} collector confirms the
 *  reactive subscription posture; SettingsRepositoryImpl `:data` impl
 *  verified at cluster20 sibling sweep (Task #476) — wraps the 5-pref
 *  flow combine plus cache-folder size walk under the io dispatcher.
 *  (b) "Contract §6 SRP owns ONE rule — return the SettingsRepository's
 *  flow; future filtering / derivation (e.g., a `derived: Boolean = down-
 *  loadedOnly plus incognito` field) would live in either the snapshot
 *  data class or a sibling use case, never here" — LIVE-NOT-STALE plus
 *  FORECAST-NOT-YET-FULFILLED. L26 realization `repository.observe-
 *  Settings()` single-line pass-through; recursive search for cross-pref
 *  derived-field composition returns zero matches — the snapshot remains
 *  the flat-record shape as cluster20 verified. Forecast posture
 *  preserved verbatim.
 *  (c) "Contract §6 DIP — depends on the `:domain` SettingsRepository
 *  interface; consumers (the rework SettingsViewModel) depend on this
 *  class, never on the impl" — LIVE-NOT-STALE. Peer pure-delegate posture
 *  verified at cluster115 sibling sweep (Task #571 — ObserveAppThemeUse-
 *  Case) plus cluster116 sibling sweep (Task #572 — ObserveSelected-
 *  LanguageUseCase). The presentation-side DIP is honored — SettingsView-
 *  Model.kt L144 ctor binds the use-case type, not the repository type.
 *  (d) "Lifecycle — bound as factory in `settingsReworkModule`;
 *  stateless plus cheap to instantiate; no internal state" — LIVE-NOT-
 *  STALE. SettingsReworkModule.kt L123 `factory { ObserveSettingsUse-
 *  Case(get()) }` realization confirms factory lifecycle. The module-
 *  level cluster20 sibling sweep (Task #476) verified the broader
 *  settingsReworkModule strangler-fig posture surviving the §301
 *  Settings-swap plus §354 legacy-retire.
 *  Four classifications STAND on their own merits as a faithful Observe-
 *  SettingsUseCase manifest. Original Phase 7.x.settings.foundation-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveSettingsUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke(): Flow<SettingsSnapshot> = repository.observeSettings()
}
