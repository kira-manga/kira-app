package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519ConfigSignatureVerifierTest {
    private val verifier = Ed25519ConfigSignatureVerifier(mapOf(KEY_ID to PUBLIC_KEY))

    @Test
    fun accepts_backend_compatible_manifest_and_source_revision_signatures() {
        assertTrue(verifier.verifyManifest(goldenManifest()))
        assertTrue(verifier.verifySource(goldenEntry(), goldenArtifact()))
    }

    @Test
    fun manifest_verification_fails_closed_for_missing_key_and_tampering() {
        val manifest = goldenManifest()
        assertFalse(Ed25519ConfigSignatureVerifier(emptyMap()).verifyManifest(manifest))
        assertFalse(verifier.verifyManifest(manifest.copy(payload = manifest.payload + " ")))
        assertFalse(verifier.verifyManifest(manifest.copy(metadata = manifest.metadata.copy(revision = 99))))
        assertFalse(
            verifier.verifyManifest(
                manifest.copy(metadata = manifest.metadata.copy(checksum = "0".repeat(64))),
            ),
        )
        assertFalse(
            verifier.verifyManifest(
                manifest.copy(metadata = manifest.metadata.copy(signatureBase64 = "not-base64")),
            ),
        )
    }

    @Test
    fun source_verification_fails_closed_for_identity_revision_checksum_and_signature_tampering() {
        val entry = goldenEntry()
        val artifact = goldenArtifact()
        assertFalse(verifier.verifySource(entry.copy(api = "Other"), artifact))
        assertFalse(verifier.verifySource(entry.copy(sourceRevision = 8), artifact))
        assertFalse(verifier.verifySource(entry.copy(checksum = "0".repeat(64)), artifact))
        assertFalse(verifier.verifySource(entry.copy(sourceSignature = "not-base64"), artifact))
        assertFalse(verifier.verifySource(entry, artifact.copy(payload = artifact.payload + " ")))
    }

    private fun goldenManifest(): SignedSourceCatalogManifest =
        SignedSourceCatalogManifest(
            payload = MANIFEST_PAYLOAD,
            metadata =
                ConfigSignatureMetadata(
                    format = "kira-source-catalog-manifest-v1",
                    algorithm = "Ed25519",
                    keyId = KEY_ID,
                    signatureBase64 = MANIFEST_SIGNATURE,
                    revision = 100,
                    checksum = MANIFEST_CHECKSUM,
                    createdAt = "2026-07-23T00:00:00Z",
                ),
        )

    private fun goldenEntry(): SourceCatalogEntry =
        SourceCatalogEntry(
            api = "Azora",
            sourceRevision = 7,
            checksum = SOURCE_CHECKSUM,
            order = 0,
            lifecycle = "active",
            engine = "generic",
            sourceSigningKeyId = KEY_ID,
            sourceSignature = SOURCE_SIGNATURE,
        )

    private fun goldenArtifact(): SourceRevisionArtifact =
        SourceRevisionArtifact(
            api = "Azora",
            sourceRevision = 7,
            checksum = SOURCE_CHECKSUM,
            canonVersion = "kcj-1",
            payload = SOURCE_PAYLOAD,
        )

    private companion object {
        const val KEY_ID = "golden-v2"
        const val PUBLIC_KEY =
            "MCowBQYDK2VwAyEAmZGKpkASPeGMnuieZhW04j9+SdfSWDYDm+mSW8ilVFY="
        const val MANIFEST_PAYLOAD =
            """{"schemaVersion":1,"sourceSchemaVersion":1,"catalogRevision":100,"generatedAt":""" +
                """"2026-07-23T00:00:00Z","sources":[],"removedSources":[]}"""
        const val MANIFEST_CHECKSUM =
            "d2e591ee5764ba81af69ca4ea2e1306f63cd2d06eac76325d461bfa4623a2bc5"
        const val MANIFEST_SIGNATURE =
            "4xVkoj2/iC0a9ziUPmaBfnljw5uK6hBCucGl31Dve0TWl27ahh5EyiqT94LGb8oA+" +
                "3wuZYRcKyjEVJMOSGkPAA=="
        const val SOURCE_PAYLOAD =
            """{"api":"Azora","language":"(AR)","baseUrl":"https://api.example.test","engine":"generic"}"""
        const val SOURCE_CHECKSUM =
            "8e54b05e1c9395e78aad87cb4b108f4bd8e17a3cd0d1c2ec07c25e4485d2d271"
        const val SOURCE_SIGNATURE =
            "7m2eVirxjpMfkMKEHhUJWXFygWG4CaivudLyC5ORbhy60zBnxDGadkDjy6qMPFxs+" +
                "Mr1WqnO3r2N/cGpSp9/CQ=="
    }
}
