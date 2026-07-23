package me.manga.kira.sources.runtime

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.SignedConfigDocument
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import kotlin.io.encoding.Base64

/** Verifies the backend's exact signed-byte contract against application-pinned X.509 keys. */
class Ed25519ConfigSignatureVerifier(
    private val pinnedPublicKeys: Map<String, String>,
) : ConfigSignatureVerifier, SourceCatalogSignatureVerifier {
    override fun verify(document: SignedConfigDocument): Boolean =
        runCatching {
            val metadata = document.metadata
            require(metadata.format == SIGNATURE_FORMAT)
            require(metadata.algorithm == ALGORITHM)
            require(KEY_ID.matches(metadata.keyId))
            require(metadata.revision > 0)
            require(CHECKSUM.matches(metadata.checksum))
            require(CREATED_AT.matches(metadata.createdAt))
            require((metadata.previousRevision == null) == (metadata.previousChecksum == null))
            metadata.previousRevision?.let { require(it > 0 && it < metadata.revision) }
            metadata.previousChecksum?.let { require(CHECKSUM.matches(it)) }

            val payloadBytes = document.payload.encodeToByteArray()
            require(payloadBytes.size <= MAX_DOCUMENT_BYTES)
            require(sha256Hex(payloadBytes) == metadata.checksum)
            val publicKeyBytes = Base64.decode(requireNotNull(pinnedPublicKeys[metadata.keyId]))
            val signatureBytes = Base64.decode(metadata.signatureBase64)
            require(signatureBytes.size == ED25519_SIGNATURE_BYTES)

            val edDsa = CryptographyProvider.Default.get(EdDSA)
            val publicKey =
                edDsa
                    .publicKeyDecoder(EdDSA.Curve.Ed25519)
                    .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.DER, publicKeyBytes)
            publicKey.signatureVerifier().tryVerifySignatureBlocking(signaturePayload(document), signatureBytes)
        }.getOrDefault(false)

    override fun verifyManifest(manifest: SignedSourceCatalogManifest): Boolean =
        runCatching {
            val metadata = manifest.metadata
            require(metadata.format == MANIFEST_SIGNATURE_FORMAT)
            requireValidMetadata(metadata)
            val payloadBytes = manifest.payload.encodeToByteArray()
            require(payloadBytes.size <= MAX_DOCUMENT_BYTES)
            require(sha256Hex(payloadBytes) == metadata.checksum)
            verifySignature(
                keyId = metadata.keyId,
                signatureBase64 = metadata.signatureBase64,
                payload =
                    buildString {
                        append(metadata.format).append('\n')
                        append(metadata.revision).append('\n')
                        append(metadata.previousRevision ?: 0).append('\n')
                        append(metadata.previousChecksum ?: "-").append('\n')
                        append(metadata.checksum).append('\n')
                        append(metadata.createdAt).append('\n')
                        append(manifest.payload)
                    }.encodeToByteArray(),
            )
        }.getOrDefault(false)

    override fun verifySource(
        entry: SourceCatalogEntry,
        artifact: SourceRevisionArtifact,
    ): Boolean =
        runCatching {
            require(entry.api == artifact.api)
            require(entry.sourceRevision == artifact.sourceRevision)
            require(entry.checksum == artifact.checksum)
            require(artifact.canonVersion == CANON_VERSION)
            require(CHECKSUM.matches(entry.checksum))
            require(sha256Hex(artifact.payload.encodeToByteArray()) == entry.checksum)
            verifySignature(
                keyId = entry.sourceSigningKeyId,
                signatureBase64 = entry.sourceSignature,
                payload =
                    buildString {
                        append(SOURCE_SIGNATURE_FORMAT).append('\n')
                        append(entry.api).append('\n')
                        append(entry.sourceRevision).append('\n')
                        append(entry.checksum).append('\n')
                        append(artifact.payload)
                    }.encodeToByteArray(),
            )
        }.getOrDefault(false)

    private fun requireValidMetadata(metadata: me.manga.kira.sources.contracts.ConfigSignatureMetadata) {
        require(metadata.algorithm == ALGORITHM)
        require(KEY_ID.matches(metadata.keyId))
        require(metadata.revision > 0)
        require(CHECKSUM.matches(metadata.checksum))
        require(CREATED_AT.matches(metadata.createdAt))
        require((metadata.previousRevision == null) == (metadata.previousChecksum == null))
        metadata.previousRevision?.let { require(it > 0 && it < metadata.revision) }
        metadata.previousChecksum?.let { require(CHECKSUM.matches(it)) }
    }

    private fun verifySignature(
        keyId: String,
        signatureBase64: String,
        payload: ByteArray,
    ): Boolean {
        val publicKeyBytes = Base64.decode(requireNotNull(pinnedPublicKeys[keyId]))
        val signatureBytes = Base64.decode(signatureBase64)
        require(signatureBytes.size == ED25519_SIGNATURE_BYTES)
        val publicKey =
            CryptographyProvider.Default
                .get(EdDSA)
                .publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.DER, publicKeyBytes)
        return publicKey.signatureVerifier().tryVerifySignatureBlocking(payload, signatureBytes)
    }

    private fun signaturePayload(document: SignedConfigDocument): ByteArray {
        val metadata = document.metadata
        return buildString {
            append(SIGNATURE_FORMAT).append('\n')
            append(metadata.revision).append('\n')
            append(metadata.previousRevision ?: 0).append('\n')
            append(metadata.previousChecksum ?: "-").append('\n')
            append(metadata.checksum).append('\n')
            append(metadata.createdAt).append('\n')
            append(document.payload)
        }.encodeToByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes).joinToString("") { byte ->
            byte.toUByte().toString(HEX_RADIX).padStart(HEX_BYTE_WIDTH, '0')
        }

    private companion object {
        const val SIGNATURE_FORMAT = "kira-source-signature-v1"
        const val MANIFEST_SIGNATURE_FORMAT = "kira-source-catalog-manifest-v1"
        const val SOURCE_SIGNATURE_FORMAT = "kira-source-revision-v1"
        const val CANON_VERSION = "kcj-1"
        const val ALGORITHM = "Ed25519"
        const val ED25519_SIGNATURE_BYTES = 64
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
        const val HEX_RADIX = 16
        const val HEX_BYTE_WIDTH = 2
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val CHECKSUM = Regex("[0-9a-f]{64}")
        val CREATED_AT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
    }
}
