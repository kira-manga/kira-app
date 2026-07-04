package me.manga.kira.domain.usecase.settings

import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.domain.repository.SettingsRepository

/**
 * Mutate one of the [SettingsToggle] booleans — thin pass-through to
 * [SettingsRepository.setToggle].
 *
 * Phase 7.x.settings.foundation rework. Pure delegation; no business logic.
 *
 * Contract §6 SRP: ONE rule — write one toggle. The enum-payload dispatch lives in the `:data`
 * impl (an exhaustive `when (toggle)` maps each variant to the matching legacy setter).
 *
 * Contract §6 OCP: adding a further [SettingsToggle] variant (e.g., `NOTIFICATION_SOUND`) becomes
 * a compile-time error in the `:data` impl's `when` (forced to handle the new variant). This use
 * case itself doesn't change — the enum dispatch is pure data, the use case body remains the
 * one-line `repository.setToggle(toggle, value)`.
 *
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface; consumers (the
 * rework `SettingsViewModel`) depend on this class, never on the impl.
 *
 * Lifecycle: bound as `factory` — stateless and cheap to instantiate.
 *
 * Return shape: `Result<Unit>` from the underlying repo. Success → caller emits a success
 * snackbar (or trusts the flow update to reflect the change); failure → caller emits an error
 * snackbar with the throwable's message. Same posture as §95's Complaint action use cases.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster117.staleKdocSweep.cascade,
 * Task #573, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-seventh sibling of the cluster57-116 sweep — wave-17
 * `:domain/usecase/settings/` batch alongside ObserveSettingsUseCase.kt
 * plus ClearCacheUseCase.kt):
 *  (a) "Phase 7.x.settings.foundation rework — pure delegation; no
 *  business logic" — LIVE-NOT-STALE. SettingsViewModel.kt L146 primary
 *  constructor binds `private val updateToggle: UpdateSettingsToggleUse-
 *  Case`; L181 `SettingsIntent.OnToggleChange rename-to viewModelScope.
 *  launch { updateToggle(intent.toggle, intent.value) }` realization
 *  confirms the fire-and-forget posture; cluster107 sibling sweep (Task
 *  #563) verified the intent-launch posture.
 *  (b) "Contract §6 SRP owns ONE rule — write one toggle; the enum-
 *  payload dispatch lives in the `:data` impl (an exhaustive `when
 *  (toggle)` maps each variant to the matching legacy setter)" — LIVE-
 *  NOT-STALE. L32-33 realization `repository.setToggle(toggle, value)`
 *  single-line pass-through; SettingsRepositoryImpl `:data` impl
 *  exhaustive `when (toggle)` mapper verified at cluster20 sibling sweep
 *  (Task #476) — each SettingsToggle variant resolves to the matching
 *  legacy `SharedPreferences.putBoolean` cell.
 *  (c) "Contract §6 OCP — adding a 6th SettingsToggle variant (e.g.,
 *  NOTIFICATION_SOUND) becomes a compile-time error in the `:data` impl's
 *  `when` (forced to handle the new variant); this use case itself
 *  doesn't change — the enum dispatch is pure data, the use case body
 *  remains the one-line `repository.setToggle(toggle, value)`" — LIVE-
 *  NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Recursive search for a 6th
 *  SettingsToggle variant returns zero matches — the enum remains the 5-
 *  variant shape verified at cluster20. The OCP closure-around-this-use-
 *  case posture holds: even when a 6th variant lands, only the `:data`
 *  `when` widens; this use case stays unchanged. Forecast posture
 *  preserved verbatim.
 *  (d) "Contract §6 DIP — depends on the `:domain` SettingsRepository
 *  interface; consumers (the rework SettingsViewModel) depend on this
 *  class, never on the impl; return shape Result<Unit> — Same posture as
 *  §95's Complaint action use cases" — LIVE-NOT-STALE. Peer mutator
 *  Result<Unit> posture verified at cluster26 sibling sweep (Task #482 —
 *  SetSourceEnabledUseCase) plus cluster115 sibling sweep (Task #571 —
 *  SetAppThemeUseCase rename-to suspend Unit return without Result wrap,
 *  divergent because legacy `SharedPreferences.putBoolean` is sync /
 *  infallible — SettingsRepository.setToggle stays Result<Unit> because
 *  the underlying combine plus refresh-trigger orchestration can throw
 *  per cluster20). Return-shape divergence captured for the audit trail.
 *  (e) "Lifecycle — bound as factory; stateless plus cheap to
 *  instantiate" — LIVE-NOT-STALE. SettingsReworkModule.kt L124 `factory
 *  { UpdateSettingsToggleUseCase(get()) }` realization confirms factory
 *  lifecycle (stateless, cheap to construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Update-
 *  SettingsToggleUseCase manifest. Original Phase 7.x.settings.foundation-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class UpdateSettingsToggleUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(toggle: SettingsToggle, value: Boolean): Result<Unit> =
        repository.setToggle(toggle, value)
}
