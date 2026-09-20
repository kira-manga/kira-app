package me.manga.kira.data.identity

import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

/** A rejected comparison is preserved/reported, not evidence that this row can be rewritten. */
data class ExcludedWorkOwner(
    val owner: SavedWorkIdentity,
    val rejection: WorkAliasRejection,
)

/**
 * Only Missing permits a new owner attempt under the same transaction. Found retains the stored
 * raw address and does not grant rewrite authority. Excluded rows remain untouched even when an
 * unrelated request succeeds; strict migration must check/reject its own affected unsupported row.
 */
sealed interface WorkOwnerResolution {
    val excluded: List<ExcludedWorkOwner>

    data class Found(
        val owner: SavedWorkIdentity,
        override val excluded: List<ExcludedWorkOwner>,
    ) : WorkOwnerResolution

    data class Missing(override val excluded: List<ExcludedWorkOwner>) : WorkOwnerResolution

    data class Conflict(
        val reason: WorkOwnerConflict,
        val owners: List<SavedWorkIdentity>,
        override val excluded: List<ExcludedWorkOwner>,
    ) : WorkOwnerResolution
}

/** Explicit failures; none is an INSERT-IGNORE/title-fallback or owner-substitution signal. */
sealed interface WorkOwnerConflict {
    data object CrossApiExactUrl : WorkOwnerConflict
    data object MultipleAuthorizedOwners : WorkOwnerConflict
    data object InconsistentCandidates : WorkOwnerConflict
    data class RejectedRequest(val rejection: WorkAliasRejection) : WorkOwnerConflict
    data object RetainedOwnerMissing : WorkOwnerConflict
    data object RetainedOwnerChanged : WorkOwnerConflict
}

/**
 * Pure owner selection; callers supply a complete, transaction-consistent same-source candidate
 * set plus the independently queried GLOBAL exact-URL owner. Never source-filter that global read.
 * The policy token must be acquired/revalidated after obtaining the owning writer transaction.
 * This resolver does not lock Room, repair data, rewrite URLs, fetch a client or choose by title.
 */
class WorkOwnerResolver(private val policy: WorkAliasPolicy) {
    /** Retain this token with the operation; a later policy requires fresh resolution. */
    val token: AcceptedCatalogToken get() = policy.token

    /**
     * Scans all authorized owners, including aliases when an exact row exists. Unsupported unowned
     * requests conflict; exact existing same-api rows remain recoverable without usable alias rules.
     * Unsupported other rows are excluded, not global blockers for valid unrelated requests.
     */
    fun resolve(
        request: WorkLocator,
        globalExactUrlOwner: SavedWorkIdentity?,
        sameSourceCandidates: List<SavedWorkIdentity>,
    ): WorkOwnerResolution {
        inputConflict(request, globalExactUrlOwner, sameSourceCandidates)?.let { return it }
        val scan = scanCandidates(request, sameSourceCandidates)
        if (scan.owners.size > 1) {
            return WorkOwnerResolution.Conflict(
                WorkOwnerConflict.MultipleAuthorizedOwners,
                scan.owners,
                scan.excluded,
            )
        }
        scan.owners.singleOrNull()?.let { return WorkOwnerResolution.Found(it, scan.excluded) }
        val rejection = policy.unownedRequestRejection(request)
        return if (rejection == null) {
            WorkOwnerResolution.Missing(scan.excluded)
        } else {
            WorkOwnerResolution.Conflict(WorkOwnerConflict.RejectedRequest(rejection), emptyList(), scan.excluded)
        }
    }

    /**
     * Retained IDs are fences, never insertion hints. Resolve the full family again and require the
     * same saved ID; deletion/replacement cannot silently redirect a pending operation to a new row.
     * A proven accepted alias may change the retained row's URL, but cannot change its local ID.
     */
    fun resolveRetained(
        retained: SavedWorkIdentity,
        globalExactUrlOwner: SavedWorkIdentity?,
        sameSourceCandidates: List<SavedWorkIdentity>,
    ): WorkOwnerResolution =
        when (val resolved = resolve(retained.locator, globalExactUrlOwner, sameSourceCandidates)) {
            is WorkOwnerResolution.Found -> if (resolved.owner.id == retained.id) {
                resolved
            } else {
                WorkOwnerResolution.Conflict(
                    WorkOwnerConflict.RetainedOwnerChanged,
                    listOf(resolved.owner),
                    resolved.excluded,
                )
            }
            is WorkOwnerResolution.Missing -> WorkOwnerResolution.Conflict(
                WorkOwnerConflict.RetainedOwnerMissing,
                emptyList(),
                resolved.excluded,
            )
            is WorkOwnerResolution.Conflict -> resolved
        }

    private fun scanCandidates(request: WorkLocator, candidates: List<SavedWorkIdentity>): OwnerScan {
        val owners = mutableListOf<SavedWorkIdentity>()
        val excluded = mutableListOf<ExcludedWorkOwner>()
        candidates.distinct().forEach { candidate ->
            when (val compared = policy.compare(request, candidate.locator)) {
                WorkAliasComparison.Exact, WorkAliasComparison.DeclaredAlias -> owners += candidate
                WorkAliasComparison.Distinct -> Unit
                is WorkAliasComparison.Rejected -> excluded += ExcludedWorkOwner(candidate, compared.reason)
            }
        }
        return OwnerScan(owners.sortedBy { it.id }, excluded.sortedBy { it.owner.id })
    }

    private fun inputConflict(
        request: WorkLocator,
        exact: SavedWorkIdentity?,
        candidates: List<SavedWorkIdentity>,
    ): WorkOwnerResolution.Conflict? {
        if (exact != null && exact.locator.url != request.url) return inconsistent(listOf(exact))
        if (exact != null && exact.locator.api != request.api) {
            return WorkOwnerResolution.Conflict(WorkOwnerConflict.CrossApiExactUrl, listOf(exact), emptyList())
        }
        if (candidates.any { it.locator.api != request.api }) return inconsistent(candidates)
        if (candidates.groupBy { it.id }.values.any { it.distinct().size > 1 }) return inconsistent(candidates)
        val exactCandidates = candidates.filter { it.locator.url == request.url }
        if ((exact == null && exactCandidates.isNotEmpty()) || (exact != null && exact !in exactCandidates)) {
            return inconsistent(candidates + listOfNotNull(exact))
        }
        return null
    }

    private fun inconsistent(owners: List<SavedWorkIdentity>) = WorkOwnerResolution.Conflict(
        WorkOwnerConflict.InconsistentCandidates,
        owners.distinct().toList(),
        emptyList(),
    )
}

private data class OwnerScan(
    val owners: List<SavedWorkIdentity>,
    val excluded: List<ExcludedWorkOwner>,
)
