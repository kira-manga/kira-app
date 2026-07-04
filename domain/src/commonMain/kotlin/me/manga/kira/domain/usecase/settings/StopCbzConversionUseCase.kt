package me.manga.kira.domain.usecase.settings

import me.manga.kira.domain.repository.SettingsRepository

/**
 * Request cancellation of the in-flight "compress existing downloads" CBZ run (GAP-SET-16) — thin
 * pass-through to [SettingsRepository.stopConversion].
 *
 * Phase 7.x.settings.cbz rework. Pure delegation; no business logic. Fired from the `:ui`
 * `CbzConversionDialog`'s Stop button via the `:presentation` `SettingsViewModel`'s
 * `OnStopConversion` intent.
 *
 * Native parity: mirrors the native `CbzConversionViewModel.stopConversion()` entry point. The
 * underlying impl finishes the current chapter (no mid-archive corruption), then emits a terminal
 * Stopped progress snapshot.
 *
 * Contract §6 SRP: ONE rule — invoke the stop-conversion action.
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface.
 *
 * Lifecycle: bound as `factory` — stateless. Non-suspend: the stop flag flip is synchronous; the
 * loop observes it on its next iteration.
 */
class StopCbzConversionUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke() = repository.stopConversion()
}
