package me.manga.kira.sources.runtime

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.SourceCatalogDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.ActiveSourceCatalogEntity
import me.manga.kira.data.local.entity.SourceCatalogEntryEntity
import me.manga.kira.data.local.entity.SourceCatalogManifestEntity
import me.manga.kira.data.local.entity.SourceRevisionArtifactEntity
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.repository.SourceUrlMigrator
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** Room v12 implementation of the all-or-nothing source-catalog store. */
class RoomSourceCatalogStore(
    private val database: MangaDatabase,
    private val catalogDao: SourceCatalogDao,
    private val sourcesDao: SourcesDao,
    private val migrator: SourceUrlMigrator,
    private val bundledJson: String,
) : SourceCatalogStore {
    override fun readBundled(): String = bundledJson

    override suspend fun projectBundled(document: SourceConfigDocument) {
        require(document.sources.all { it.engine == ENGINE_GENERIC && it.lifecycle == LIFECYCLE_ACTIVE })
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                projectActiveSources(document.sources)
            }
        }
    }

    override suspend fun readActive(): StoredSourceCatalog? {
        val manifestEntity = catalogDao.activeManifest() ?: return null
        val manifest = manifestEntity.toContract()
        val parsed =
            when (val result = SourceConfigParser.parseManifest(manifest.payload)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return null
            }
        val entriesByApi = catalogDao.entries(parsed.catalogRevision).associateBy { it.api }
        if (entriesByApi.size != parsed.sources.size) return null
        val sources =
            parsed.sources.filter { it.lifecycle == LIFECYCLE_ACTIVE }.map { entry ->
                val persisted = entriesByApi[entry.api] ?: return null
                if (!persisted.matches(entry.sourceRevision, entry.checksum)) return null
                catalogDao.source(entry.api, entry.sourceRevision, entry.checksum)?.toContract()
                    ?: return null
            }
        return StoredSourceCatalog(manifest, sources)
    }

    override suspend fun findSource(
        api: String,
        sourceRevision: Long,
        checksum: String,
    ): SourceRevisionArtifact? = catalogDao.source(api, sourceRevision, checksum)?.toContract()

    override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? =
        catalogDao.activePointer()?.let {
            SourceCatalogAcceptanceFloor(it.catalogRevision, it.checksum)
        }

    override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? =
        catalogDao.activeManifest()?.toContract()

    override suspend fun activate(catalog: StoredSourceCatalog) {
        val manifest =
            when (val result = SourceConfigParser.parseManifest(catalog.manifest.payload)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> error("refusing to persist an invalid manifest")
            }
        val activeApis =
            manifest.sources
                .filter { it.lifecycle == LIFECYCLE_ACTIVE }
                .mapTo(mutableSetOf()) { it.api }
        val sourceConfigs = parseActiveConfigs(activeApis, catalog.sources)
        val metadata = catalog.manifest.metadata
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                ensureImmutableSourceRevisions(catalog.sources)
                ensureImmutableManifest(catalog.manifest)
                catalogDao.insertSources(catalog.sources.map { it.toEntity() })
                catalogDao.insertManifest(catalog.manifest.toEntity())
                catalogDao.deleteEntries(manifest.catalogRevision)
                catalogDao.insertEntries(
                    manifest.sources.map { entry ->
                        SourceCatalogEntryEntity(
                            catalogRevision = manifest.catalogRevision,
                            api = entry.api,
                            sourceRevision = entry.sourceRevision,
                            checksum = entry.checksum,
                            displayOrder = entry.order,
                            lifecycle = entry.lifecycle,
                            engine = entry.engine,
                            sourceSigningKeyId = entry.sourceSigningKeyId,
                            sourceSignature = entry.sourceSignature,
                        )
                    },
                )
                projectActiveSources(sourceConfigs)
                catalogDao.setActive(
                    ActiveSourceCatalogEntity(
                        catalogRevision = metadata.revision,
                        checksum = metadata.checksum,
                    ),
                )
            }
        }
    }

    private suspend fun projectActiveSources(configs: List<SourceConfig>) {
        val existing = sourcesDao.getAllSourcesOnce().associateBy { it.name }
        configs.forEachIndexed { order, config ->
            val row = existing[config.api]
            if (row == null) {
                sourcesDao.insert(config.toSourceEntity(order))
            } else {
                applyHostChanges(config, row)
            }
            sweepPreviousHosts(config)
            sourcesDao.updateCatalogMetadata(
                api = config.api,
                priority = order,
                language = config.language,
                siteState = config.sourceState(),
            )
        }
        if (configs.isEmpty()) {
            existing.keys.forEach { sourcesDao.deleteSourceByName(it) }
        } else {
            sourcesDao.deleteOutsideCatalog(configs.map { it.api })
        }
    }

    private suspend fun applyHostChanges(
        config: SourceConfig,
        row: SourcesEntity,
    ) {
        if (config.baseUrl.isNotBlank() &&
            row.baseUrl != config.baseUrl &&
            !isUserMirror(row.baseUrl, config.baseUrl, config.previousHosts)
        ) {
            migrator.migratePageUrlsStrict(config.api, config.baseUrl, urlHost(row.baseUrl)?.let(::setOf))
            sourcesDao.updateBaseUrlAndVersionByName(config.api, config.baseUrl, row.baseVersion + 1)
        }
        if (config.imageBase.isNotBlank() &&
            row.imageBaseUrl != config.imageBase &&
            !isUserMirror(row.imageBaseUrl, config.imageBase, config.previousImageHosts)
        ) {
            migrator.migrateImageUrlsStrict(config.api, config.imageBase, urlHost(row.imageBaseUrl)?.let(::setOf))
            sourcesDao.updateImageBaseUrlAndVersionByName(
                config.api,
                config.imageBase,
                row.imageUrlVersion + 1,
            )
        }
    }

    private suspend fun sweepPreviousHosts(config: SourceConfig) {
        if (config.previousHosts.isNotEmpty() && config.baseUrl.isNotBlank()) {
            migrator.migratePageUrlsStrict(
                config.api,
                config.baseUrl,
                config.previousHosts.mapTo(mutableSetOf(), String::lowercase),
            )
        }
        if (config.previousImageHosts.isNotEmpty() && config.imageBase.isNotBlank()) {
            migrator.migrateImageUrlsStrict(
                config.api,
                config.imageBase,
                config.previousImageHosts.mapTo(mutableSetOf(), String::lowercase),
            )
        }
    }

    private fun isUserMirror(
        rowUrl: String,
        configUrl: String,
        previousHosts: List<String>,
    ): Boolean {
        if (previousHosts.isEmpty()) return false
        val rowHost = urlHost(rowUrl)
        return rowHost != null &&
            rowHost != urlHost(configUrl) &&
            previousHosts.none { it.lowercase() == rowHost }
    }

    private fun urlHost(url: String): String? =
        runCatching {
            val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            authority.substringBefore(':').lowercase().takeIf(String::isNotBlank)
        }.getOrNull()

    private fun parseActiveConfigs(
        manifestApis: Set<String>,
        artifacts: List<SourceRevisionArtifact>,
    ): List<SourceConfig> {
        require(artifacts.size == manifestApis.size) {
            "active source artifacts must exactly match the manifest"
        }
        require(artifacts.mapTo(mutableSetOf()) { it.api } == manifestApis) {
            "active source artifacts must exactly match the manifest"
        }
        return artifacts.map { artifact ->
            require(artifact.api in manifestApis)
            when (val result = SourceConfigParser.parseSource(artifact.payload)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> error("refusing to project an invalid source")
            }
        }
    }

    private suspend fun ensureImmutableSourceRevisions(artifacts: List<SourceRevisionArtifact>) {
        artifacts.forEach { artifact ->
            val existing = catalogDao.sourceByIdentity(artifact.api, artifact.sourceRevision)
            if (existing != null) requireSameImmutableSourceRevision(existing, artifact)
        }
    }

    private suspend fun ensureImmutableManifest(manifest: SignedSourceCatalogManifest) {
        val existing = catalogDao.manifestByRevision(manifest.metadata.revision) ?: return
        require(
            existing.checksum == manifest.metadata.checksum &&
                existing.rawPayload == manifest.payload &&
                existing.signingKeyId == manifest.metadata.keyId &&
                existing.signatureBase64 == manifest.metadata.signatureBase64,
        ) {
            "immutable catalog revision conflict for revision=${manifest.metadata.revision}"
        }
    }

    private fun SignedSourceCatalogManifest.toEntity(): SourceCatalogManifestEntity =
        SourceCatalogManifestEntity(
            catalogRevision = metadata.revision,
            rawPayload = payload,
            format = metadata.format,
            algorithm = metadata.algorithm,
            signingKeyId = metadata.keyId,
            signatureBase64 = metadata.signatureBase64,
            checksum = metadata.checksum,
            createdAt = metadata.createdAt,
            previousRevision = metadata.previousRevision,
            previousChecksum = metadata.previousChecksum,
        )

    private fun SourceCatalogManifestEntity.toContract(): SignedSourceCatalogManifest =
        SignedSourceCatalogManifest(
            payload = rawPayload,
            metadata =
                ConfigSignatureMetadata(
                    format = format,
                    algorithm = algorithm,
                    keyId = signingKeyId,
                    signatureBase64 = signatureBase64,
                    revision = catalogRevision,
                    checksum = checksum,
                    createdAt = createdAt,
                    previousRevision = previousRevision,
                    previousChecksum = previousChecksum,
                ),
        )

    private fun SourceRevisionArtifact.toEntity(): SourceRevisionArtifactEntity =
        SourceRevisionArtifactEntity(api, sourceRevision, checksum, canonVersion, payload)

    private fun SourceRevisionArtifactEntity.toContract(): SourceRevisionArtifact =
        SourceRevisionArtifact(api, sourceRevision, checksum, canonVersion, rawPayload)

    private fun SourceCatalogEntryEntity.matches(
        revision: Long,
        expectedChecksum: String,
    ): Boolean = sourceRevision == revision && checksum == expectedChecksum

    private fun SourceConfig.toSourceEntity(order: Int): SourcesEntity =
        SourcesEntity(
            name = api,
            isEnabled = enabled,
            priority = order,
            language = language,
            siteState = sourceState(),
            baseUrl = baseUrl,
            imageBaseUrl = imageBase,
            imageUrlVersion = 0,
        )

    private fun SourceConfig.sourceState(): SourceState =
        runCatching { SourceState.valueOf(siteState) }.getOrDefault(SourceState.WORKING)
}

private const val LIFECYCLE_ACTIVE = "active"
private const val ENGINE_GENERIC = "generic"

internal fun requireSameImmutableSourceRevision(
    existing: SourceRevisionArtifactEntity,
    candidate: SourceRevisionArtifact,
) {
    require(
        existing.api == candidate.api &&
            existing.sourceRevision == candidate.sourceRevision &&
            existing.checksum == candidate.checksum &&
            existing.canonVersion == candidate.canonVersion &&
            existing.rawPayload == candidate.payload,
    ) {
        "immutable source revision conflict for api=${candidate.api} revision=${candidate.sourceRevision}"
    }
}
