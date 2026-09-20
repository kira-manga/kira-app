package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.SourceSelectionUnavailable

/** No shipping binding is implied; construction requires the real Room selection coordinator. */
class RoomSourceAliasSnapshotProvider(private val selections: RoomSourceSelectionCommit) : SourceAliasSnapshotProvider {
    // Equal Ready tokens must still invalidate a writer that observed the coalesced unavailable interval.
    override val readiness: Flow<SourceAliasReadiness> = combine(
        selections.readiness.transitions, selections.invalidations,
    ) { transition, _ ->
        val binding = transition.binding
        if (binding == null) SourceAliasReadiness.NotReady else {
            val durable = try { selections.read().selection } catch (_: SourceSelectionUnavailable) { null }
            if (matches(binding, durable)) SourceAliasReadiness.Ready(requireNotNull(durable).token) else SourceAliasReadiness.NotReady
        }
    }.onStart { emit(SourceAliasReadiness.NotReady) }

    override suspend fun readInTransaction(): SourceAliasSnapshot {
        val binding = selections.readiness.transitions.value.binding
        val durable = selections.readInTransaction().selection
        if (binding == null || !matches(binding, durable)) throw SourceSelectionUnavailable("effective selection not ready")
        return snapshot(requireNotNull(durable))
    }

    private fun matches(binding: ProcessVerifiedSourceSelection, durable: CommittedSourceSelection?): Boolean =
        durable != null && binding.receipt == durable &&
            EffectiveSourceSelectionCodec.digest(binding.raw) == durable.token.payloadDigest

    private fun snapshot(selection: CommittedSourceSelection) = SourceAliasSnapshot(
        selection.token,
        selection.payload.rules.map { AcceptedSourceAliasRule(it.api, it.currentBaseUrl, it.previousHosts) },
    )
}
