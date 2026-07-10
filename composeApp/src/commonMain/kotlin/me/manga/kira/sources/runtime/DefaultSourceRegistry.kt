package me.manga.kira.sources.runtime

import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.toRuntimeDescriptor
import me.manga.kira.sources_repositry.BaseMangaRepository

/**
 * The composition-root assembly of [SourceRegistry]. It owns the per-api decision of which
 * [MangaSourceClient] backs a source:
 *
 *  - **Legacy adapter (non-config sources):** every shipped [BaseMangaRepository] is wrapped in a
 *    [LegacyKotlinSourceClient]. With [configBackedApis] empty this is the only path taken. Non-config sources
 *    are disabled/hidden from the active flow (legacy isolation), so this adapter is effectively inert
 *    in the user-facing runtime — it is returned but never exercised for an active source.
 *  - **Generic (config-backed) — generic-ONLY, no legacy fallback:** if an api is in [configBackedApis] AND the
 *    active config document describes it with `engine = "generic"`, the source is served by the
 *    config-driven client built by [genericClientFactory] and returned **bare**. A generic failure is
 *    surfaced as-is (a clear `AppResult.Failure`); the legacy scraper is **never** executed for a
 *    config-backed source. (Previously this was wrapped in [FallbackSourceClient] over the legacy
 *    adapter; that silent fallback was removed so config-backed sources are strictly config-driven.)
 *
 * Fail-closed: if a config-backed api has no valid `generic` config (e.g. the bundled config failed to
 * parse or validate), [get] returns the plain legacy adapter. The generic path is taken only when a
 * validated config is actually present.
 */
class DefaultSourceRegistry(
    legacyRepos: Set<BaseMangaRepository>,
    private val updateManager: SourceUpdateManager,
    private val genericClientFactory: (SourceConfig) -> MangaSourceClient,
    private val configBackedApis: Set<String> = emptySet(),
) : SourceRegistry {
    private val legacyClients: Map<String, MangaSourceClient> =
        legacyRepos.associate { it.API to LegacyKotlinSourceClient(it) }

    override fun get(api: String): MangaSourceClient? {
        val legacy = legacyClients[api]
        if (api in configBackedApis) {
            val config = genericConfigFor(api)
            if (config != null) {
                // Config-backed → generic ONLY. No FallbackSourceClient: a generic failure is surfaced
                // as a clear AppResult.Failure and the legacy scraper is never executed for this source.
                return genericClientFactory(config)
            }
        }
        return legacy // fail-closed: no valid generic config → legacy adapter (inert for non-config sources)
    }

    override fun isConfigBacked(api: String): Boolean = api in configBackedApis && genericConfigFor(api) != null

    override fun descriptor(api: String): RuntimeSourceDescriptor? =
        updateManager
            .activeDocument()
            .sources
            .firstOrNull { it.api == api }
            ?.toRuntimeDescriptor()

    override fun genericDescriptors(): List<RuntimeSourceDescriptor> =
        updateManager
            .activeDocument()
            .sources
            .filter { it.engine == "generic" }
            .map { it.toRuntimeDescriptor() }

    private fun genericConfigFor(api: String): SourceConfig? =
        updateManager.activeDocument().sources.firstOrNull { it.api == api && it.engine == "generic" }
}
