package me.manga.kira.domain.usecase.settings

import me.manga.kira.domain.repository.SettingsRepository

/**
 * Reset the observable CBZ-conversion progress back to idle (#14) — thin pass-through to
 * [SettingsRepository.clearConversionProgress].
 *
 * Fired from the `:ui` `CbzConversionDialog`'s dismiss path via the `:presentation`
 * `SettingsViewModel`'s `OnDismissConversionDialog` intent (after its in-converting guard), so a
 * terminal Complete/Stopped/Error snapshot does not replay into a recreated VM's freshly opened
 * dialog.
 *
 * Native parity: mirrors the native `CbzConversionViewModel.clearError()` entry point.
 *
 * Contract §6 SRP: ONE rule — reset the conversion-progress flow.
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface.
 *
 * Lifecycle: bound as `factory` — stateless. Non-suspend: the reset is a synchronous
 * `MutableStateFlow` value swap.
 */
class ClearCbzConversionUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke() = repository.clearConversionProgress()
}
