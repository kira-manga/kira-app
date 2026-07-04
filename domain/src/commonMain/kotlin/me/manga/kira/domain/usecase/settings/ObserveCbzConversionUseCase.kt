package me.manga.kira.domain.usecase.settings

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.repository.SettingsRepository

/**
 * Observe the progress of the "compress existing downloads" CBZ run (GAP-SET-16) — thin
 * pass-through to [SettingsRepository.observeCbzConversion].
 *
 * Phase 7.x.settings.cbz rework. Pure delegation; no business logic. The `:presentation`
 * `SettingsViewModel` collects this in an `init {}` collector and projects each
 * [CbzConversionProgress] emission into its MVI state so the `:ui` `CbzConversionDialog` renders
 * the determinate progress + counts + current item + Stop button + terminal states.
 *
 * Contract §6 SRP: ONE rule — observe the conversion progress stream.
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface.
 *
 * Lifecycle: bound as `factory` — stateless.
 */
class ObserveCbzConversionUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke(): Flow<CbzConversionProgress> = repository.observeCbzConversion()
}
