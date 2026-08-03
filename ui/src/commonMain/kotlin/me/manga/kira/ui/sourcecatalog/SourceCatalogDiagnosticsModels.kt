package me.manga.kira.ui.sourcecatalog

enum class SourceCatalogOriginUi {
    BUNDLED,
    VERIFIED_CACHE,
    BACKEND,
}

enum class SourceCatalogSyncStatusUi {
    ACTIVE,
    REFRESHING,
    FAILED,
}

data class SourceCatalogDiagnosticsUiModel(
    val origin: SourceCatalogOriginUi,
    val syncStatus: SourceCatalogSyncStatusUi,
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
    val activeSources: List<SourceCatalogSourceUiModel>,
)

data class SourceCatalogSourceUiModel(
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
