package me.manga.kira.sources.runtime

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import me.manga.kira.sources.contracts.EffectiveSourceSelectionPayload
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionProofRef

/** Local strict versioned codec. It neither authenticates provenance nor implements backend kcj-1. */
object EffectiveSourceSelectionCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun encode(payload: EffectiveSourceSelectionPayload): String {
        validate(payload)
        return json.encodeToString(EffectiveSourceSelectionPayload.serializer(), payload).also(::requireBounded)
    }

    fun decode(raw: String, expectedDigest: String): EffectiveSourceSelectionPayload {
        requireBounded(raw)
        require(digest(raw) == expectedDigest) { "selected payload digest mismatch" }
        return json.decodeFromString(EffectiveSourceSelectionPayload.serializer(), raw).also(::validate)
    }

    fun digest(raw: String): String = CryptographyProvider.Default.get(SHA256).hasher()
        .hashBlocking(raw.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private fun requireBounded(raw: String) {
        require(raw.length <= SourceSelectionLimits.PAYLOAD_BYTES)
        require(raw.encodeToByteArray().size <= SourceSelectionLimits.PAYLOAD_BYTES)
    }

    private fun validate(payload: EffectiveSourceSelectionPayload) {
        require(payload.version == SourceSelectionLimits.PAYLOAD_VERSION)
        require(payload.rules.size <= SourceSelectionLimits.SOURCES)
        require(payload.rules.map { it.api }.distinct().size == payload.rules.size)
        val references = mutableSetOf<SourceSelectionProofRef>()
        payload.rules.forEach { rule ->
            require(rule.api == rule.currentProof.api && rule.currentProof.catalog == payload.identity)
            require(rule.previousHosts.size <= SourceSelectionLimits.PREVIOUS_HOSTS)
            require(rule.previousHosts.map { it.host.lowercase() }.distinct().size == rule.previousHosts.size)
            references += rule.currentProof
            rule.previousHosts.forEach { previous ->
                previous.proof?.let { require(it.api == rule.api); references += it }
            }
        }
        require(references.size <= SourceSelectionLimits.PROOF_REFERENCES)
    }
}
