package me.manga.kira.data.repository.library

import me.manga.kira.domain.model.identity.SavedWorkIdentity

/**
 * Composition-owned download-engine exclusion. An empty queue or a best-effort cancel does not
 * prove quiescence. A production implementation is required before library removal is wired.
 */
interface LibraryRemovalGuard {
    /**
     * Stop/join conflicting work before invoking [block], prevent new work/ownership migration,
     * and retain the lease until artifact/file cleanup AND the final database removal finish.
     * Never invoke [block] if a requested retained owner cannot be safely quiesced.
     */
    suspend fun <T> withQuiescentWorks(owners: List<SavedWorkIdentity>, block: suspend () -> T): T

    /** Revalidate the held lease and current queue ownership inside the owning writer transaction. */
    suspend fun checkInTransaction(owners: List<SavedWorkIdentity>)
}
