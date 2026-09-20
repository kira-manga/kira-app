package me.manga.kira.data.identity

import me.manga.kira.data.repository.selection.SelectionPlanningProgress
import me.manga.kira.data.repository.selection.SelectionVisit
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

/**
 * Complete Stage-A batch for supported requests ONLY, not general exact recovery or insertion.
 * Room supplies complete ID-ordered same-source candidates. For supported requests their rejected
 * set is invariant; parsed other-scheme candidates stay Distinct. All Exact/DeclaredAlias owners
 * share the validated scheme/suffix key. No filtered list is passed to the scalar resolver.
 */
internal class SelectionWorkOwnerIndex private constructor(
    private val reference: WorkLocator,
    private val rewrites: SelectionUrlRewrites,
    private val progress: SelectionPlanningProgress,
) {
    private val complete = mutableListOf<SavedWorkIdentity>()
    private val byId = mutableMapOf<Long, SavedWorkIdentity>()
    private val byRaw = mutableMapOf<String, MutableSet<SavedWorkIdentity>>()
    private val owners = mutableMapOf<SelectionAliasKey, MutableList<SavedWorkIdentity>>()
    private val excluded = mutableListOf<ExcludedWorkOwner>()
    private var invalidCandidates = false

    suspend fun resolve(
        request: WorkLocator,
        globalExact: SavedWorkIdentity?,
    ): WorkOwnerResolution {
        val key = rewrites.key(request)
        check(request.api == reference.api)
        progress.visit(SelectionVisit.OWNER)
        return when {
            globalExact != null && globalExact.locator.url != request.url -> inconsistent(listOf(globalExact))
            globalExact != null && globalExact.locator.api != request.api ->
                WorkOwnerResolution.Conflict(WorkOwnerConflict.CrossApiExactUrl, listOf(globalExact), emptyList())
            invalidCandidates -> inconsistent(complete)
            !hasConsistentExact(request.url, globalExact) -> inconsistent(complete, globalExact)
            else -> resolveOwners(key)
        }
    }

    private fun hasConsistentExact(
        url: String,
        globalExact: SavedWorkIdentity?,
    ): Boolean {
        val exacts = byRaw[url].orEmpty()
        return if (globalExact == null) exacts.isEmpty() else globalExact in exacts
    }

    private fun resolveOwners(key: SelectionAliasKey): WorkOwnerResolution {
        val matches = owners[key].orEmpty()
        return when (matches.size) {
            0 -> WorkOwnerResolution.Missing(excluded)
            1 -> WorkOwnerResolution.Found(matches[0], excluded)
            else -> WorkOwnerResolution.Conflict(WorkOwnerConflict.MultipleAuthorizedOwners, matches, excluded)
        }
    }

    /** Once per complete source set; excluded affected owners cannot disappear behind indexed hits. */
    suspend fun rejectAffectedExclusions() {
        for (entry in excluded) {
            progress.visit(SelectionVisit.OWNER)
            if (rewrites.read(entry.owner.locator).affected) rewrites.key(entry.owner.locator)
        }
    }

    private suspend fun add(candidate: SavedWorkIdentity) {
        progress.visit(SelectionVisit.OWNER)
        val prior = byId.put(candidate.id, candidate)
        if (prior == candidate) return
        complete += candidate
        if (prior != null || candidate.locator.api != reference.api) invalidCandidates = true
        byRaw.getOrPut(candidate.locator.url) { linkedSetOf() }.add(candidate)
        val read = rewrites.read(candidate.locator)
        when (val comparison = rewrites.compare(reference, candidate.locator)) {
            is WorkAliasComparison.Rejected -> excluded += ExcludedWorkOwner(candidate, comparison.reason)
            else -> read.key?.let { owners.getOrPut(it) { mutableListOf() }.add(candidate) }
        }
    }

    private suspend fun inconsistent(
        candidates: List<SavedWorkIdentity>,
        extra: SavedWorkIdentity? = null,
    ): WorkOwnerResolution.Conflict {
        val seen = mutableSetOf<SavedWorkIdentity>()
        val unique = mutableListOf<SavedWorkIdentity>()
        for (candidate in candidates) {
            progress.visit(SelectionVisit.OWNER)
            if (seen.add(candidate)) unique += candidate
        }
        extra?.let { if (seen.add(it)) unique += it }
        return WorkOwnerResolution.Conflict(WorkOwnerConflict.InconsistentCandidates, unique, emptyList())
    }

    companion object {
        suspend fun prepare(
            reference: WorkLocator,
            completeCandidates: List<SavedWorkIdentity>,
            rewrites: SelectionUrlRewrites,
            progress: SelectionPlanningProgress,
        ): SelectionWorkOwnerIndex {
            rewrites.key(reference)
            val index = SelectionWorkOwnerIndex(reference, rewrites, progress)
            for (candidate in completeCandidates) index.add(candidate)
            return index
        }
    }
}
