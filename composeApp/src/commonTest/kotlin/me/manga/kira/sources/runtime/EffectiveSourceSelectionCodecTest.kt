package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.EffectiveSourceSelectionPayload
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SelectedSourceAliasRule
import me.manga.kira.sources.contracts.SelectedSourceRevision
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Local exact-byte persistence only; checksums are not proof authentication or backend canonical JSON. */
class EffectiveSourceSelectionCodecTest {
    @Test
    fun exact_utf8_bytes_and_all_identity_policy_and_provenance_fields_are_bound() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", EffectiveSourceSelectionCodec.digest("abc"))
        val payload = payload()
        val raw = EffectiveSourceSelectionCodec.encode(payload)
        val digest = EffectiveSourceSelectionCodec.digest(raw)
        assertEquals(payload, EffectiveSourceSelectionCodec.decode(raw, digest))
        assertNotEquals(digest, EffectiveSourceSelectionCodec.digest("$raw "))
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.decode("$raw ", digest) }
        assertEquals(payload, EffectiveSourceSelectionCodec.decode("$raw ", EffectiveSourceSelectionCodec.digest("$raw ")))
        val changed = payload.copy(rules = payload.rules.map { rule -> rule.copy(previousHosts = emptyList()) })
        assertNotEquals(digest, EffectiveSourceSelectionCodec.digest(EffectiveSourceSelectionCodec.encode(changed)))
        assertNotEquals(digest, EffectiveSourceSelectionCodec.digest(raw.replace("old.test", "öld.test")))
    }

    @Test
    fun missing_malformed_unknown_version_and_unknown_fields_never_decode_as_ready_empty() {
        val raw = EffectiveSourceSelectionCodec.encode(payload())
        listOf("", "{}", "{", "[]", raw.replace("\"version\":1", "\"version\":2"),
            raw.dropLast(1) + ",\"unknown\":1}", raw.replace("\"rules\"", "\"missingRules\"")).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid.take(120)) {
                EffectiveSourceSelectionCodec.decode(invalid, EffectiveSourceSelectionCodec.digest(invalid))
            }
        }
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.decode(raw, "A".repeat(64)) }
        val empty = payload().copy(rules = emptyList())
        val encoded = EffectiveSourceSelectionCodec.encode(empty)
        assertEquals(empty, EffectiveSourceSelectionCodec.decode(encoded, EffectiveSourceSelectionCodec.digest(encoded)))
    }

    @Test
    fun invalid_identity_authority_state_and_source_revision_pairs_are_rejected() {
        val raw = EffectiveSourceSelectionCodec.encode(payload())
        listOf(raw.replace("\"revision\":10", "\"revision\":0"),
            raw.replace("PROVEN_COMPATIBLE_ROOT", "UNKNOWN"),
            raw.replace("\"kind\":\"SIGNED\"", "\"kind\":\"BUNDLED\""),
            raw.replace("a".repeat(64), "z".repeat(64))).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                EffectiveSourceSelectionCodec.decode(invalid, EffectiveSourceSelectionCodec.digest(invalid))
            }
        }
    }

    @Test
    fun duplicate_api_or_host_and_cross_api_or_catalog_current_proofs_are_rejected() {
        val payload = payload()
        val rule = payload.rules.single()
        val prior = rule.previousHosts.single()
        val invalid = listOf(
            payload.copy(rules = listOf(rule, rule)),
            payload.copy(rules = listOf(rule.copy(previousHosts = listOf(prior, prior.copy(host = "OLD.test"))))),
            payload.copy(rules = listOf(rule.copy(currentProof = rule.currentProof.copy(api = "another")))),
            payload.copy(rules = listOf(rule.copy(currentProof = rule.currentProof.copy(catalog = identity(11))))),
            payload.copy(rules = listOf(rule.copy(previousHosts = listOf(prior.copy(proof = prior.proof?.copy(api = "another")))))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.encode(it) } }
    }

    @Test
    fun limits_apply_to_utf8_bytes_sources_previous_hosts_and_unique_proof_references() {
        val payload = payload()
        val rule = payload.rules.single()
        val oversized = payload.copy(rules = listOf(rule.copy(currentBaseUrl = "é".repeat(SourceSelectionLimits.PAYLOAD_BYTES / 2 + 1))))
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.encode(oversized) }
        val excessSources = (0..SourceSelectionLimits.SOURCES).map { index ->
            rule.copy(api = "s$index", currentProof = rule.currentProof.copy(api = "s$index"), previousHosts = emptyList())
        }
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.encode(payload.copy(rules = excessSources)) }
        val excessHosts = (0..SourceSelectionLimits.PREVIOUS_HOSTS).map { SelectedPreviousHost("old-$it.test", PreviousHostAuthority.UNKNOWN, null) }
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.encode(payload.copy(rules = listOf(rule.copy(previousHosts = excessHosts)))) }
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.encode(payload.copy(rules = excessiveReferences())) }
        assertFailsWith<IllegalArgumentException> { EffectiveSourceSelectionCodec.decode(" ".repeat(SourceSelectionLimits.PAYLOAD_BYTES + 1), "a".repeat(64)) }
    }

    private fun excessiveReferences(): List<SelectedSourceAliasRule> = (0..7).map { source ->
        val api = "s$source"
        val previous = (0 until SourceSelectionLimits.PREVIOUS_HOSTS).map { index ->
            SelectedPreviousHost("old-$source-$index.test", PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
                SourceSelectionProofRef(identity(20L + source * 256 + index), api, SelectedSourceRevision(1, "a".repeat(64))))
        }
        SelectedSourceAliasRule(api, "https://current.test", SourceSelectionProofRef(identity(10), api,
            SelectedSourceRevision(1, "a".repeat(64))), previous)
    }

    private fun payload(): EffectiveSourceSelectionPayload {
        val current = SourceSelectionProofRef(identity(10), "source", SelectedSourceRevision(1, "a".repeat(64)))
        val previous = SelectedPreviousHost("old.test", PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
            current.copy(catalog = identity(9)))
        return EffectiveSourceSelectionPayload(1, current.catalog,
            listOf(SelectedSourceAliasRule("source", "https://current.test", current, listOf(previous))))
    }

    private fun identity(revision: Long) = SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, revision, "a".repeat(64))
}
