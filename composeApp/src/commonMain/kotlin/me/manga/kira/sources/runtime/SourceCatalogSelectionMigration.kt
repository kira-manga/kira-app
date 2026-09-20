package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.VerifiedSourceSelection

/**
 * Required composition seam; deliberately NO production default/no-op implementation.
 *
 * Acquire real queue/worker exclusion outside Room and retain it across block. Inside Room, prove
 * complete affected saved/anchor families, global exact occupancy, retained IDs/raw URLs and queue
 * state under the PRIVATE candidate. Reject unproved/colliding/unsupported affected groups before
 * rewriting any member. Preserve IDs/epochs and cleanup receipt originals; no file/network work.
 * Exclusion covers image/projection whole-row writes too, not just page URL changes. Never use the
 * public committed provider to authorize the uncommitted candidate or call a nested repository.
 */
interface SourceCatalogSelectionMigration {
    suspend fun <T> withQuiescentSelection(candidate: VerifiedSourceSelection, block: suspend () -> T): T

    suspend fun migrateInTransaction(candidate: VerifiedSourceSelection, token: SourceSelectionToken)
}
