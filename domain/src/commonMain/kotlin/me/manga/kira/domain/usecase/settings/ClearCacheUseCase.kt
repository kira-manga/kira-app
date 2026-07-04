package me.manga.kira.domain.usecase.settings

import me.manga.kira.domain.repository.SettingsRepository

/**
 * Clear cache files larger than 1MB — thin pass-through to [SettingsRepository.clearLargeCache].
 *
 * Phase 7.x.settings.foundation rework. Pure delegation; no business logic.
 *
 * Contract §6 SRP: ONE rule — invoke the cache-clear action. The 1MB threshold + okio file
 * walk lives in the legacy `:shared` `AppFileSystem` (`clearCacheLargerThan(ONE_MB)`); the
 * `:data` impl is a thin adapter; this use case is a thin pass-through. Three layers of thin
 * adaptation, each with one rule.
 *
 * Contract §6 OCP: adding a `ClearAllCacheUseCase` (no size threshold) or `ClearMangaCovers
 * OnlyUseCase` (per-directory) would be sibling use cases; this one stays closed.
 *
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface.
 *
 * Lifecycle: bound as `factory` — stateless.
 *
 * Side effect: after a successful clear, the repository's `observeSettings()` flow re-emits a
 * new snapshot with the updated `cacheSize` — the VM doesn't need to re-trigger the observe
 * manually. See [SettingsRepository.clearLargeCache] KDoc for the refresh-trigger mechanism in
 * the `:data` impl.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster117.staleKdocSweep.cascade,
 * Task #573, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-seventh sibling of the cluster57-116 sweep — closes
 * the wave-17 `:domain/usecase/settings/` batch alongside ObserveSettings-
 * UseCase.kt plus UpdateSettingsToggleUseCase.kt):
 *  (a) "Phase 7.x.settings.foundation rework — pure delegation; no
 *  business logic" — LIVE-NOT-STALE. SettingsViewModel.kt L147 primary
 *  constructor binds `private val clearCache: ClearCacheUseCase`; L204
 *  `val result = clearCache()` realization inside the OnClearCache intent
 *  handler confirms the suspend-call posture (Result<Unit> consumed for
 *  snackbar dispatch); cluster107 sibling sweep (Task #563) verified the
 *  intent-handler posture.
 *  (b) "Contract §6 SRP owns ONE rule — invoke the cache-clear action;
 *  the 1MB threshold plus okio file walk lives in the legacy `:shared`
 *  AppFileSystem (`clearCacheLargerThan(ONE_MB)`); the `:data` impl is a
 *  thin adapter; this use case is a thin pass-through; three layers of
 *  thin adaptation, each with one rule" — LIVE-NOT-STALE. L30 realization
 *  `repository.clearLargeCache()` single-line pass-through; the three-
 *  layer adaptation (`:domain` UseCase rename-to `:data` Impl rename-to
 *  legacy `:shared` AppFileSystem) verified at cluster20 sibling sweep
 *  (Task #476) — the okio file walk on the io dispatcher plus the 1MB
 *  threshold constant both reside in the legacy facade.
 *  (c) "Contract §6 OCP — adding a `ClearAllCacheUseCase` (no size
 *  threshold) or `ClearMangaCoversOnlyUseCase` (per-directory) would be
 *  sibling use cases; this one stays closed" — FORECAST-NOT-YET-
 *  FULFILLED. Recursive search for `ClearAllCacheUseCase` plus
 *  `ClearMangaCoversOnlyUseCase` symbols returns zero matches; the use
 *  case remains the single-target 1MB-threshold-clear shape. Forecast
 *  posture preserved verbatim.
 *  (d) "Contract §6 DIP — depends on the `:domain` SettingsRepository
 *  interface" — LIVE-NOT-STALE. Peer pure-delegate posture verified at
 *  cluster26 sibling sweep (Task #482) plus cluster115/116 sibling sweeps.
 *  Side-effect framing — "after a successful clear, the repository's
 *  observeSettings() flow re-emits a new snapshot with the updated
 *  cacheSize" — LIVE-NOT-STALE: the `:data` impl's `MutableSharedFlow<
 *  Unit>` refresh trigger (the `single` lifecycle rationale captured in
 *  SettingsReworkModule.kt L73-75 cluster20 postscript) emits on
 *  successful clear, the upstream `combine` re-runs, the cache-size cell
 *  re-walks the cache folder, plus the snapshot re-emits.
 *  (e) "Lifecycle — bound as factory; stateless" — LIVE-NOT-STALE.
 *  SettingsReworkModule.kt L125 `factory { ClearCacheUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to construct,
 *  never shared).
 *  Five classifications STAND on their own merits as a faithful Clear-
 *  CacheUseCase manifest. Original Phase 7.x.settings.foundation-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
class ClearCacheUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.clearLargeCache()
}
