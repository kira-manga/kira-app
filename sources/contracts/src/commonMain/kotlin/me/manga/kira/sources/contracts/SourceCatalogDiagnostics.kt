package me.manga.kira.sources.contracts

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only, safe metadata describing the complete source catalog currently used by the app.
 *
 * Signatures and source payloads are deliberately excluded. Consumers can explain provenance and
 * versions without gaining access to authenticated document contents or cryptographic material.
 */
interface SourceCatalogDiagnosticsProvider {
    val diagnostics: StateFlow<SourceCatalogDiagnostics>
}

/** Safe manifest-level metadata for the single complete catalog tier currently in use. */
data class SourceCatalogDiagnostics(
    val origin: UpdateState.Origin,
    val catalogRevision: Long,
    val catalogSchemaVersion: Int,
    val sourceSchemaVersion: Int,
    val generatedAt: String?,
    val manifestChecksum: String?,
    val manifestSigningKeyId: String?,
    val signatureAlgorithm: String?,
    val signatureFormat: String?,
    val previousCatalogRevision: Long?,
    val previousCatalogChecksum: String?,
    val removedSourceCount: Int,
    val inactiveSourceCount: Int,
    val activeSources: List<ActiveSourceDiagnostics>,
)

/** Safe identity and immutable-version metadata for one active generic source. */
data class ActiveSourceDiagnostics(
    val api: String,
    val displayName: String,
    val language: String,
    val baseUrl: String,
    val engine: String,
    val lifecycle: String,
    val order: Int,
    val sourceRevision: Long?,
    val checksum: String?,
    val signingKeyId: String?,
)
