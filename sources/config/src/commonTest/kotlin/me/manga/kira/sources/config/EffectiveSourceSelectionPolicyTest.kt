package me.manga.kira.sources.config

import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedSourceRevision
import me.manga.kira.sources.contracts.SourceSelectionLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Policy/provenance derivation with the existing synthetic verifier, not signature implementation tests. */
class EffectiveSourceSelectionPolicyTest {
    @Test
    fun previous_host_declarations_alone_are_unknown_and_do_not_create_proofs() {
        val current = verifiedCatalogAt(20, "https://current.test", listOf("older.test", "old.test"))
        val candidate = selectionCandidate(current, null, emptyList(), true)
        assertEquals(listOf("old.test", "older.test"), candidate.payload.rules.single().previousHosts.map { it.host })
        assertTrue(candidate.payload.rules.single().previousHosts.all { it.authority == PreviousHostAuthority.UNKNOWN && it.proof == null })
        assertEquals(current.proofs, candidate.proofs)
    }

    @Test
    fun one_accepted_root_proves_only_its_host_not_an_unknown_sibling() {
        val old = verifiedCatalogAt(10, "https://old.test")
        val previous = selectionCandidate(old, null, emptyList(), false)
        val current = verifiedCatalogAt(20, "https://current.test", listOf("old.test", "unproved.test"))
        val candidate = selectionCandidate(current, previous.payload, previous.proofs, true)
        val hosts = candidate.payload.rules.single().previousHosts.associateBy { it.host }
        assertEquals(PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT, hosts.getValue("old.test").authority)
        assertEquals(old.proofs.single().reference, hosts.getValue("old.test").proof)
        assertEquals(PreviousHostAuthority.UNKNOWN, hosts.getValue("unproved.test").authority)
        assertNull(hosts.getValue("unproved.test").proof)
        assertEquals(2, candidate.proofs.size)
    }

    @Test
    fun known_base_path_scheme_or_nondefault_port_changes_remain_incompatible() {
        listOf("https://old.test/prefix", "http://old.test", "https://old.test:8443").forEach { oldBase ->
            val old = verifiedCatalogAt(10, oldBase)
            val previous = selectionCandidate(old, null, emptyList(), false)
            val current = verifiedCatalogAt(20, "https://current.test", listOf("old.test"))
            val host = selectionCandidate(current, previous.payload, previous.proofs, true).payload.rules.single().previousHosts.single()
            assertEquals(PreviousHostAuthority.PROVEN_INCOMPATIBLE, host.authority, oldBase)
            assertEquals(old.proofs.single().reference, host.proof, oldBase)
        }
    }

    @Test
    fun normalized_supported_root_scheme_and_default_ports_are_compatible() {
        listOf("https" to "443", "http" to "80").forEach { (scheme, port) ->
            val old = verifiedCatalogAt(10, "${scheme.uppercase()}://OLD.test:$port/")
            val previous = selectionCandidate(old, null, emptyList(), false)
            val current = verifiedCatalogAt(20, "$scheme://current.test", listOf("OLD.test"))
            val host = selectionCandidate(current, previous.payload, previous.proofs, true).payload.rules.single().previousHosts.single()
            assertEquals("old.test", host.host)
            assertEquals(PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT, host.authority)
        }
    }

    @Test
    fun latest_relevant_descriptor_beats_older_convenient_root_and_missing_latest_stays_unknown() {
        val root = selectionCandidate(verifiedCatalogAt(10, "https://old.test"), null, emptyList(), false)
        val later = selectionCandidate(verifiedCatalogAt(11, "https://old.test/prefix", listOf("old.test")), root.payload, root.proofs, true)
        val current = verifiedCatalogAt(12, "https://current.test", listOf("old.test"))
        val proved = selectionCandidate(current, later.payload, later.proofs, true).payload.rules.single().previousHosts.single()
        assertEquals(PreviousHostAuthority.PROVEN_INCOMPATIBLE, proved.authority)
        assertEquals(later.payload.rules.single().currentProof, proved.proof)
        val missing = selectionCandidate(current, later.payload, root.proofs, true).payload.rules.single().previousHosts.single()
        assertEquals(PreviousHostAuthority.UNKNOWN, missing.authority)
        assertNull(missing.proof, "do not fall back to the older retained compatible root")
    }

    @Test
    fun stored_current_base_text_cannot_relabel_or_hide_an_authenticated_latest_descriptor() {
        val previous = selectionCandidate(verifiedCatalogAt(10, "https://unrelated.test"), null, emptyList(), false)
        val forged = previous.payload.copy(rules = previous.payload.rules.map { it.copy(currentBaseUrl = "https://old.test") })
        val current = verifiedCatalogAt(20, "https://current.test", listOf("old.test"))
        val host = selectionCandidate(current, forged, previous.proofs, true).payload.rules.single().previousHosts.single()
        assertEquals(PreviousHostAuthority.UNKNOWN, host.authority)
        assertNull(host.proof)
        val root = selectionCandidate(verifiedCatalogAt(9, "https://old.test"), null, emptyList(), false)
        val latest = selectionCandidate(verifiedCatalogAt(10, "https://old.test/prefix", listOf("old.test")), root.payload, root.proofs, true)
        val hidden = latest.payload.copy(rules = latest.payload.rules.map { it.copy(currentBaseUrl = "https://unrelated.test") })
        val incompatible = selectionCandidate(current, hidden, latest.proofs, true).payload.rules.single().previousHosts.single()
        assertEquals(PreviousHostAuthority.PROVEN_INCOMPATIBLE, incompatible.authority)
        assertEquals(latest.payload.rules.single().currentProof, incompatible.proof)
    }

    @Test
    fun signed_historical_authority_requires_accepted_manifest_entry_and_exact_artifact_identity() = runTest {
        val old = verifiedCatalogAt(10, "https://old.test")
        val stored = requireNotNull(old.stored)
        val store = FakeCatalogStore(null)
        val verifier = SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {})
        store.stage(stored.sources.single())
        assertTrue(verifier.historical(old.proofs.map { it.reference }, verifier.bundle()).isEmpty())
        store.acceptHistory(stored.manifest)
        assertEquals(old.proofs, verifier.historical(old.proofs.map { it.reference }, verifier.bundle()))
        val wrong = old.proofs.single().reference.copy(source = SelectedSourceRevision(11, checksum(11)))
        assertTrue(verifier.historical(listOf(wrong), verifier.bundle()).isEmpty())
        store.stage(stored.sources.single().copy(payload = sourceJson("other")))
        assertTrue(verifier.historical(old.proofs.map { it.reference }, verifier.bundle()).isEmpty())
    }

    @Test
    fun only_current_embedded_bundle_can_supply_unsigned_historical_proof() = runTest {
        val store = FakeCatalogStore(null)
        val verifier = SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {})
        val original = verifier.bundle()
        assertEquals(original.proofs, verifier.historical(original.proofs.map { it.reference }, original))
        store.bundleJson = bundledJson().replace("floor.test", "different.test")
        assertTrue(verifier.historical(original.proofs.map { it.reference }, verifier.bundle()).isEmpty())
    }

    @Test
    fun failed_historical_artifact_lookups_still_spend_the_manifest_evidence_budget() = runTest {
        val store = FakeCatalogStore(null)
        val verifier = SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {})
        val refs = (10L..16L).map { revision ->
            val catalog = verifiedCatalogAt(revision, "https://old.test")
            val manifest = requireNotNull(catalog.stored).manifest
            store.acceptHistory(manifest.copy(payload = manifest.payload.padEnd(SourceSelectionLimits.MANIFEST_BYTES, ' ')))
            catalog.proofs.single().reference
        }
        assertFailsWith<IllegalArgumentException> { verifier.historical(refs, verifier.bundle()) }
        assertEquals(7, store.historicalReads, "the seventh bounded manifest exhausts 32 MiB before further verification/cache growth")
    }

    @Test
    fun duplicate_references_are_deduplicated_and_excess_unique_references_are_rejected_before_reads() = runTest {
        val store = FakeCatalogStore(null)
        val verifier = SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {})
        val proof = verifiedCatalogAt(10, "https://old.test").proofs.single().reference
        assertTrue(verifier.historical(listOf(proof, proof), verifier.bundle()).isEmpty())
        assertEquals(1, store.historicalReads)
        val excess = (1..SourceSelectionLimits.PROOF_REFERENCES + 1).map { revision ->
            proof.copy(catalog = proof.catalog.copy(revision = revision.toLong(), checksum = checksum(revision.toLong())))
        }
        assertFailsWith<IllegalArgumentException> { verifier.historical(excess, verifier.bundle()) }
        assertEquals(1, store.historicalReads)
    }
}
