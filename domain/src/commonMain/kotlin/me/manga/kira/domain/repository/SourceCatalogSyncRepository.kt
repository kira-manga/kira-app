package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult

/**
 * Syncs the local source catalog (`sources` table) FROM the active config document — making the
 * config the catalog source of truth (Sources Migration — Phase 2).
 *
 * For each config-backed source:
 *  - if it has no row yet, seed one (disabled by default, like the legacy seed);
 *  - if its `baseUrl` (or image base) differs from the stored value, the source's host moved, so
 *    rewrite the stored manga/chapter/history/notification URLs from the old host to the new one
 *    and update the stored base URL — `config.baseUrl` is the trusted value.
 *
 * Non-fatal: a failure must never block app launch (the startup caller ignores the result).
 */
interface SourceCatalogSyncRepository {
    suspend fun syncFromConfig(): AppResult<Unit>
}
