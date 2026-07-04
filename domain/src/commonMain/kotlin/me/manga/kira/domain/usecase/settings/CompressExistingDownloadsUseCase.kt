package me.manga.kira.domain.usecase.settings

import me.manga.kira.domain.repository.SettingsRepository

/**
 * Trigger the "compress existing downloads" action behind the Yami Compressor settings section —
 * thin pass-through to [SettingsRepository.compressExistingDownloads].
 *
 * Phase 7.x.settings.cbz rework. Pure delegation; no business logic.
 *
 * Native parity: the native `CbzConversionViewModel.startConversion()` ran the bulk CBZ conversion
 * over `ChapterDao.getAllDownloadedChapters()`. The `:data` [SettingsRepository] impl now runs the
 * same cross-platform walk (over the `:platform` `CbzWriter` SPI) and, per GAP-SET-16, drives a
 * `Flow<me.manga.kira.domain.model.settings.CbzConversionProgress>` with per-chapter progress +
 * a terminal Success / Stopped / Error snapshot; observe it via [ObserveCbzConversionUseCase] and
 * cancel via [StopCbzConversionUseCase]. This use case stays a thin pass-through for the
 * fire-and-forget trigger (the `Result<Unit>` only reports the overall pass/fail).
 *
 * Contract §6 SRP: ONE rule — invoke the convert-existing action.
 * Contract §6 DIP: depends on the `:domain` [SettingsRepository] interface.
 *
 * Lifecycle: bound as `factory` — stateless.
 */
class CompressExistingDownloadsUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.compressExistingDownloads()
}
