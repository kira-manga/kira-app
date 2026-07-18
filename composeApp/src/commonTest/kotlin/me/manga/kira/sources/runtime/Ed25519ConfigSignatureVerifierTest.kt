package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.SignedConfigDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519ConfigSignatureVerifierTest {
    private val verifier = Ed25519ConfigSignatureVerifier(mapOf(KEY_ID to PUBLIC_KEY))

    @Test
    fun accepts_backend_compatible_golden_signature() {
        assertTrue(verifier.verify(goldenDocument()))
    }

    @Test
    fun rejects_unsigned_wrong_key_tampered_stale_metadata_and_rollback_link_changes() {
        val document = goldenDocument()
        assertFalse(Ed25519ConfigSignatureVerifier(emptyMap()).verify(document))
        assertFalse(verifier.verify(document.copy(payload = document.payload + " ")))
        assertFalse(verifier.verify(document.copy(metadata = document.metadata.copy(revision = 99))))
        assertFalse(verifier.verify(document.copy(metadata = document.metadata.copy(checksum = "0".repeat(64)))))
        assertFalse(
            verifier.verify(
                document.copy(
                    metadata =
                        document.metadata.copy(
                            previousRevision = 99,
                            previousChecksum = "1".repeat(64),
                        ),
                ),
            ),
        )
        assertFalse(verifier.verify(document.copy(metadata = document.metadata.copy(signatureBase64 = "not-base64"))))
    }

    private fun goldenDocument(): SignedConfigDocument =
        SignedConfigDocument(
            payload = RAW_DOCUMENT,
            metadata =
                ConfigSignatureMetadata(
                    format = "kira-source-signature-v1",
                    algorithm = "Ed25519",
                    keyId = KEY_ID,
                    signatureBase64 = SIGNATURE,
                    revision = 100,
                    checksum = CHECKSUM,
                    createdAt = "2026-07-18T00:00:00Z",
                ),
        )

    private companion object {
        const val KEY_ID = "golden-2026-07"
        const val RAW_DOCUMENT = "{\"revision\":100,\"schemaVersion\":1,\"sources\":[]}"
        const val CHECKSUM = "ef780b3b093bc067fe47a1a58aa6d34a4c710a0e662a563f2465a0988b0f30c5"
        const val PUBLIC_KEY = "MCowBQYDK2VwAyEAoV90+w29yHfyICKUz+2+yRAjVW7LQpuC0Qja374grls="
        const val SIGNATURE = "Iis6dAVkHUrkIiMIs83zlnjEaQna5NLDfPB63Evx5t9uh5h+SLe5T/le3swMrtJtKkY/sNTLcmTpQFaikL/7BA=="
    }
}
