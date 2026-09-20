package me.manga.kira.sources.runtime

import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.local.ensureMangaWriteActive
import me.manga.kira.data.repository.selection.StrictSourceSelectionMigration
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.VerifiedSourceSelection

/** Holds actual producer exclusion over the caller's complete Room migration/projection commit. */
class DownloadSourceCatalogSelectionMigration(
    private val operations: DownloadOperationExclusion,
    private val migration: StrictSourceSelectionMigration,
) : SourceCatalogSelectionMigration {
    override suspend fun <T> withQuiescentSelection(
        candidate: VerifiedSourceSelection,
        block: suspend () -> T,
    ): T = try {
        operations.withExclusive { block() }
    } catch (_: DownloadOperationBusy) {
        throw SourceSelectionUnavailable("download ownership must settle before source selection")
    }

    override suspend fun migrateInTransaction(candidate: VerifiedSourceSelection, token: SourceSelectionToken) {
        operations.requireExclusive()
        ensureMangaWriteActive()
        // The candidate was verified and frozen by the coordinator. The public accepted provider
        // still describes the old selection and must not authorize these uncommitted rewrites.
        migration.migrateInTransaction(
            token,
            candidate.payload.rules.map { AcceptedSourceAliasRule(it.api, it.currentBaseUrl, it.previousHosts) },
        )
        ensureMangaWriteActive()
    }
}
