package me.manga.kira.domain.usecase.sources

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.SourceCatalogSyncRepository

/**
 * Sync the local source catalog from the active config document at startup (Sources Migration —
 * Phase 2). Makes the config the catalog source of truth: seeds config-backed sources into the
 * `sources` table and migrates stored URLs when a source's base URL changed.
 *
 * Contract §6 SRP: owns ONE rule — delegate to [SourceCatalogSyncRepository.syncFromConfig]. DIP:
 * depends on the `:domain` interface only. Returns [AppResult] so a future caller could surface a
 * failure; the startup caller ignores it (logs and continues) — sync must never block app launch.
 */
class SyncSourceCatalogUseCase(
    private val repository: SourceCatalogSyncRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> = repository.syncFromConfig()
}
