package me.manga.kira.sources.runtime

import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.repository.SourceUrlMigrator
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.sourceBaseUrlHost

/** Called only after the required queue/strict participant succeeds in the owning selection writer. */
class RoomSourceCatalogProjection(private val sources: SourcesDao, private val images: SourceUrlMigrator) {
    internal suspend fun project(configs: List<SourceConfig>) {
        val existing = sources.getAllSourcesOnce().associateBy { it.name }
        configs.forEachIndexed { order, config ->
            val row = existing[config.api]
            if (row == null) sources.insert(config.toSourceEntity(order)) else updateMetadata(config, row)
            if (config.previousImageHosts.isNotEmpty() && config.imageBase.isNotBlank()) {
                images.migrateImageUrlsStrict(config.api, config.imageBase, config.previousImageHosts.mapTo(mutableSetOf(), String::lowercase))
            }
            sources.updateCatalogMetadata(config.api, order, config.language, SourceState.valueOf(config.siteState))
        }
        if (configs.isEmpty()) existing.keys.forEach { sources.deleteSourceByName(it) }
        else sources.deleteOutsideCatalog(configs.map { it.api })
    }

    private suspend fun updateMetadata(config: SourceConfig, row: SourcesEntity) {
        if (config.baseUrl.isNotBlank() && row.baseUrl != config.baseUrl &&
            !isUserMirrorSourceUrl(row.baseUrl, config.baseUrl, config.previousHosts)
        ) {
            check(row.baseVersion < Int.MAX_VALUE)
            // Page ownership/migration belongs exclusively to SourceCatalogSelectionMigration.
            sources.updateBaseUrlAndVersionByName(config.api, config.baseUrl, row.baseVersion + 1)
        }
        if (config.imageBase.isNotBlank() && row.imageBaseUrl != config.imageBase &&
            !isUserMirrorSourceUrl(row.imageBaseUrl, config.imageBase, config.previousImageHosts)
        ) {
            check(row.imageUrlVersion < Int.MAX_VALUE)
            images.migrateImageUrlsStrict(config.api, config.imageBase, sourceBaseUrlHost(row.imageBaseUrl)?.let(::setOf))
            sources.updateImageBaseUrlAndVersionByName(config.api, config.imageBase, row.imageUrlVersion + 1)
        }
    }
}

private fun SourceConfig.toSourceEntity(order: Int) = SourcesEntity(
    name = api, isEnabled = enabled, priority = order, language = language,
    siteState = SourceState.valueOf(siteState), baseUrl = baseUrl, imageBaseUrl = imageBase, imageUrlVersion = 0,
)

/** Mirror metadata preserves an explicit user choice; it grants NO page alias/rewrite authority. */
internal fun isUserMirrorSourceUrl(rowUrl: String, configUrl: String, previousHosts: List<String>): Boolean {
    val rowHost = sourceBaseUrlHost(rowUrl)
    return previousHosts.isNotEmpty() && rowHost != null && rowHost != sourceBaseUrlHost(configUrl) &&
        previousHosts.none { it.lowercase() == rowHost }
}
