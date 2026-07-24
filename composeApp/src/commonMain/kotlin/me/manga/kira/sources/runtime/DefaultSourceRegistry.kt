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
 *    [LegacyKotlinSourceClient]. Non-config sources are disabled/hidden from the active flow (legacy
 *    isolation), so this adapter is effectively inert in the user-facing runtime — it is returned but
 *    never exercised for an active source.
 *  - **Generic (config-backed) — generic-ONLY, no legacy fallback:** if the VALIDATED active config
 *    document describes an api with `engine = "generic"`, the source is served by the config-driven
 *    client built by [genericClientFactory] and returned **bare**. A generic failure is surfaced
 *    as-is (a clear `AppResult.Failure`); the legacy scraper is **never** executed for a
 *    config-backed source. (Previously this was wrapped in [FallbackSourceClient] over the legacy
 *    adapter; that silent fallback was removed so config-backed sources are strictly config-driven.)
 *
 * The validated document is the SINGLE authority for which sources are generic (MangaSource
 * decoupling, 2026-07): the former `CONFIG_BACKED_APIS` in-binary allow-list double-gate was removed
 * as redundant. Remote config delivery accepts only pinned-key Ed25519 envelopes and falls back to
 * the bundled document when the endpoint is unset, unavailable, stale, rolled back, or invalid. The
 * signature covers the exact document bytes and anti-rollback metadata before an update is parsed.
 * Shipping Android builds require an endpoint and pinned keys; local builds may stay on this bundled
 * floor explicitly.
 *
 * Fail-closed: if the bundled document failed to parse or validate, the active document degrades to
 * EMPTY — no api resolves generic and [get] returns the plain legacy adapter. The generic path is
 * taken only when a validated config is actually present.
 */
class DefaultSourceRegistry(
    legacyRepos: Set<BaseMangaRepository>,
    private val updateManager: SourceUpdateManager,
    private val genericClientFactory: (SourceConfig) -> MangaSourceClient,
) : SourceRegistry {
    private val legacyClients: Map<String, MangaSourceClient> =
        legacyRepos.associate { it.API to LegacyKotlinSourceClient(it) }

    override fun get(api: String): MangaSourceClient? {
        val config = genericConfigFor(api)
        if (config != null) {
            // Config-backed → generic ONLY. No FallbackSourceClient: a generic failure is surfaced
            // as a clear AppResult.Failure and the legacy scraper is never executed for this source.
            return genericClientFactory(config)
        }
        // Fail-closed: no valid generic stanza → legacy adapter (inert for non-config sources).
        return legacyClients[api]
    }

    override fun isConfigBacked(api: String): Boolean = genericConfigFor(api) != null

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
