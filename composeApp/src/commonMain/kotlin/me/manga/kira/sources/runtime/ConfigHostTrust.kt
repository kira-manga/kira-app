package me.manga.kira.sources.runtime

import io.ktor.http.Url
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.model.SourceConfig

/**
 * R7 of the SourceRegistry retirement (`SOURCE_REGISTRY_RETIREMENT_PLAN.md` §4 Phase 3): answers
 * "is [host] one of the config-declared hosts for [api]?" for the push deep-link trust gate in
 * `App.kt`. The trusted set per source is `baseUrl` + `imageBase` hosts plus the three bare-host
 * lists (`previousHosts`, `previousImageHosts`, `trustedHosts`), each matching exactly or as a
 * parent domain (`sub.host.com` is trusted when `host.com` is declared — the same subdomain rule
 * the legacy `findRepoByHost` resolver applies).
 *
 * Config metadata is authoritative and per-api (a host declared for source A never trusts a link
 * claiming source B); the legacy resolver remains the fallback for apis without config metadata.
 * Reads the ACTIVE document on every call — no caching — so a config update is honored immediately.
 */
class ConfigHostTrust(
    private val updateManager: SourceUpdateManager,
) {
    fun ownsHost(
        api: String,
        host: String,
    ): Boolean {
        val cfg =
            if (host.isBlank()) null else updateManager.activeDocument().sources.firstOrNull { it.api == api }
        if (cfg == null) return false
        val target = host.lowercase()
        return configHosts(cfg).any { declared -> target == declared || target.endsWith(".$declared") }
    }

    /**
     * Reverse lookup (MangaSource decoupling, 2026-07): the api whose config-declared hosts match
     * [host], or null when no stanza claims it. First match in document order wins — the same
     * subdomain rule as [ownsHost]. Lets the Coil header interceptor resolve a cover URL's source
     * without a compiled legacy repo.
     */
    fun apiForHost(host: String): String? {
        if (host.isBlank()) return null
        val target = host.lowercase()
        return updateManager
            .activeDocument()
            .sources
            .firstOrNull { cfg ->
                configHosts(cfg).any { declared -> target == declared || target.endsWith(".$declared") }
            }?.api
    }

    private fun configHosts(cfg: SourceConfig): Sequence<String> =
        sequence {
            hostOf(cfg.baseUrl)?.let { yield(it) }
            hostOf(cfg.imageBase)?.let { yield(it) }
            yieldAll(cfg.previousHosts)
            yieldAll(cfg.previousImageHosts)
            yieldAll(cfg.trustedHosts)
        }.map { it.lowercase() }.filter { it.isNotBlank() }

    private fun hostOf(url: String): String? = runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
