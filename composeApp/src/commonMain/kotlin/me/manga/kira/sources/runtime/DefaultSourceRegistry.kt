package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.toRuntimeDescriptor

/**
 * Generic-only source registry. A source resolves only while the complete active catalog contains
 * an `active` generic stanza for its API. Absence and every non-active lifecycle return null; no
 * legacy client is inferred from old rows, cache contents, or network failure.
 */
class DefaultSourceRegistry(
    private val updateManager: SourceUpdateManager,
    private val genericClientFactory: (SourceConfig) -> MangaSourceClient,
) : SourceRegistry {
    override fun get(api: String): MangaSourceClient? {
        val config = genericConfigFor(api)
        return config?.let(genericClientFactory)
    }

    override fun isConfigBacked(api: String): Boolean = genericConfigFor(api) != null

    override fun descriptor(api: String): RuntimeSourceDescriptor? =
        updateManager
            .activeDocument()
            .sources
            .firstOrNull { it.api == api && it.engine == ENGINE_GENERIC && it.lifecycle == LIFECYCLE_ACTIVE }
            ?.toRuntimeDescriptor()

    override fun genericDescriptors(): List<RuntimeSourceDescriptor> =
        updateManager
            .activeDocument()
            .sources
            .filter { it.engine == ENGINE_GENERIC && it.lifecycle == LIFECYCLE_ACTIVE }
            .map { it.toRuntimeDescriptor() }

    private fun genericConfigFor(api: String): SourceConfig? =
        updateManager
            .activeDocument()
            .sources
            .firstOrNull { it.api == api && it.engine == ENGINE_GENERIC && it.lifecycle == LIFECYCLE_ACTIVE }

    private companion object {
        const val ENGINE_GENERIC = "generic"
        const val LIFECYCLE_ACTIVE = "active"
    }
}
