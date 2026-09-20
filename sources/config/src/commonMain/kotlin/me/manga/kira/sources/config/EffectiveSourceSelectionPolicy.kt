package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.EffectiveSourceSelectionPayload
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SelectedSourceAliasRule
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.VerifiedSourceSelectionProof

/** Derives authority from exact authenticated descriptors, never previousHosts alone or mutable rows. */
internal fun selectionCandidate(
    current: VerifiedCatalog,
    previous: EffectiveSourceSelectionPayload?,
    history: List<VerifiedSourceSelectionProof>,
    advancesFloor: Boolean,
): VerifiedSourceSelection {
    val evidence = (current.proofs + history).associateBy { it.reference }
    val rules = current.proofs.map { proof -> selectionRule(proof, previous, evidence) }
    val payload = EffectiveSourceSelectionPayload(SourceSelectionLimits.PAYLOAD_VERSION, current.identity, rules)
    val used = payloadReferences(payload).map { evidence.getValue(it) }
    requireEvidenceBound(used)
    return VerifiedSourceSelection(current.document, payload, current.stored, used, advancesFloor)
}

private fun selectionRule(
    current: VerifiedSourceSelectionProof,
    previous: EffectiveSourceSelectionPayload?,
    evidence: Map<SourceSelectionProofRef, VerifiedSourceSelectionProof>,
): SelectedSourceAliasRule {
    val source = current.descriptor
    val old = previous?.rules?.singleOrNull { it.api == source.api }
    require(source.previousHosts.size <= SourceSelectionLimits.PREVIOUS_HOSTS)
    val hosts = source.previousHosts.map { it.lowercase() }.distinct().sorted().map { host ->
        val reference = previousReference(host, old, evidence)
        val proof = reference?.let(evidence::get)
        val authority = when {
            proof == null -> PreviousHostAuthority.UNKNOWN
            rootOrigin(proof.descriptor.baseUrl) == null -> PreviousHostAuthority.PROVEN_INCOMPATIBLE
            rootOrigin(source.baseUrl)?.first != rootOrigin(proof.descriptor.baseUrl)?.first ->
                PreviousHostAuthority.PROVEN_INCOMPATIBLE
            rootOrigin(source.baseUrl) == null -> PreviousHostAuthority.PROVEN_INCOMPATIBLE
            else -> PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT
        }
        SelectedPreviousHost(host, authority, proof?.reference)
    }
    return SelectedSourceAliasRule(source.api, source.baseUrl, current.reference, hosts)
}

/** Latest relevant accepted current descriptor beats older convenient evidence for the same host. */
private fun previousReference(
    host: String,
    previous: SelectedSourceAliasRule?,
    evidence: Map<SourceSelectionProofRef, VerifiedSourceSelectionProof>,
): SourceSelectionProofRef? {
    if (previous == null) return null
    val latest = evidence[previous.currentProof] ?: return null
    if (descriptorHost(latest.descriptor.baseUrl) == host) return latest.reference
    if (descriptorHost(previous.currentBaseUrl) == host) return null
    val prior = previous.previousHosts.singleOrNull { it.host.lowercase() == host }?.proof ?: return null
    return prior.takeIf { descriptorHost(evidence[it]?.descriptor?.baseUrl.orEmpty()) == host }
}

internal fun payloadReferences(payload: EffectiveSourceSelectionPayload): List<SourceSelectionProofRef> =
    payload.rules.flatMap { rule -> listOf(rule.currentProof) + rule.previousHosts.mapNotNull { it.proof } }.distinct()

/** Same narrow supported DNS root origins as P1; never a general URL normalizer. */
private fun rootOrigin(raw: String): Pair<String, String>? {
    val match = ROOT.matchEntire(raw) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase()
    if (!validHost(host)) return null
    val port = match.groupValues[3]
    if (port.isNotEmpty() && port.toIntOrNull() != if (scheme == "https") 443 else 80) return null
    return scheme to host
}

private fun descriptorHost(raw: String): String? =
    raw.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringBefore(':').lowercase().takeIf(::validHost)

private fun validHost(host: String): Boolean = host.isNotEmpty() && host.length <= 253 &&
    !host.all { it in '0'..'9' || it == '.' } && host.split('.').all { label ->
        label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

private val ROOT = Regex("(https?)://([A-Za-z0-9.-]+)(?::([0-9]+))?/?", RegexOption.IGNORE_CASE)
