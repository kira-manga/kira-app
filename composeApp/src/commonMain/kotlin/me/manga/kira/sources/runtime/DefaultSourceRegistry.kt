package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.toRuntimeDescriptor

/**
 * Generic-only source registry. Catalog metadata stays visible for every `active` generic stanza
 * so the UI can render operational states such as maintenance. Executable clients resolve only
 * while that stanza is also `WORKING`. Absence, every non-active lifecycle, and every non-working
 * site state return null; no legacy client is inferred from old rows, cache contents, or network
 * failure.
 */
class DefaultSourceRegistry(
    private val updateManager: SourceUpdateManager,
    private val genericClientFactory: (SourceConfig) -> MangaSourceClient,
) : SourceRegistry {
    override fun get(api: String): MangaSourceClient? {
        val config = executableConfigFor(api)
        return config?.let(genericClientFactory)
    }

    override fun isConfigBacked(api: String): Boolean = catalogConfigFor(api) != null

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

    private fun catalogConfigFor(api: String): SourceConfig? =
        updateManager
            .activeDocument()
            .sources
            .firstOrNull { it.api == api && it.engine == ENGINE_GENERIC && it.lifecycle == LIFECYCLE_ACTIVE }

    private fun executableConfigFor(api: String): SourceConfig? =
        catalogConfigFor(api)?.takeIf { it.siteState == SITE_STATE_WORKING }

    private companion object {
        const val ENGINE_GENERIC = "generic"
        const val LIFECYCLE_ACTIVE = "active"
        const val SITE_STATE_WORKING = "WORKING"
    }
}
