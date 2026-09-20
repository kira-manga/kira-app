package me.manga.kira.data.identity

import kotlinx.coroutines.flow.Flow
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SourceSelectionToken

typealias AcceptedCatalogToken = SourceSelectionToken

/** Verified current origin is independent of each declared previous host's historical authority. */
class AcceptedSourceAliasRule(
    val api: String,
    val currentBaseUrl: String,
    previousPageHosts: Collection<SelectedPreviousHost>,
) {
    private val previousHosts = previousPageHosts.toList()
    internal fun previousPageHosts(): List<SelectedPreviousHost> = previousHosts.toList()
    internal fun authority(host: String): PreviousHostAuthority? = previousHosts
        .filter { it.host.lowercase() == host }.singleOrNull()?.authority
}

/** Immutable finite policy; duplicate API rules remain ambiguous, never first/last-wins. */
class SourceAliasSnapshot(val token: AcceptedCatalogToken, rules: Collection<AcceptedSourceAliasRule>) {
    private val rulesByApi = rules.toList().groupBy { it.api }
    internal fun ruleFor(api: String): AliasRuleLookup {
        val matches = rulesByApi[api] ?: return AliasRuleLookup.Unavailable
        return if (matches.size == 1) AliasRuleLookup.Found(matches.single()) else AliasRuleLookup.Ambiguous
    }
}

/** Invalidation only. Even Ready requires a fresh durable read AFTER acquiring the caller's writer. */
sealed interface SourceAliasReadiness {
    data object NotReady : SourceAliasReadiness
    data class Ready(val token: AcceptedCatalogToken) : SourceAliasReadiness
}

/**
 * Composition bridge, not a cached policy. Includes initial NotReady, readiness loss/recovery and
 * durable selection/allocator invalidations. Activation's candidate stays private until commit.
 */
interface SourceAliasSnapshotProvider {
    val readiness: Flow<SourceAliasReadiness>

    /** Uses the caller's owning transaction. Never waits, bootstraps, opens a writer or returns defaults. */
    suspend fun readInTransaction(): SourceAliasSnapshot
}

internal sealed interface AliasRuleLookup {
    data class Found(val rule: AcceptedSourceAliasRule) : AliasRuleLookup
    data object Unavailable : AliasRuleLookup
    data object Ambiguous : AliasRuleLookup
}
