package me.manga.kira.sources.runtime

import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.HeaderStore

/**
 * Real [HeaderStore] over the app's [DataStoreHelper] — the SAME per-api header storage the legacy
 * sources use (cookies / user-agent / Cloudflare clearance captured by the WebView solver). Wiring
 * this (rather than the empty Stage-0 stub) is what lets the generic engine reuse headers a user
 * already captured for a `usesCapturedHeaders=true` source like Lekmanga, so the generic path can
 * actually succeed live. (A header-free source such as Azora skips this store entirely.) Read-only
 * here; the WebView solver remains the writer.
 */
class DataStoreHeaderStore(
    private val dataStore: DataStoreHelper,
) : HeaderStore {
    override suspend fun headersFor(api: String): Map<String, String> =
        dataStore.getHeadersForApi(api) ?: emptyMap()

    override suspend fun save(api: String, headers: Map<String, String>) =
        dataStore.saveHeadersForApi(api, headers)
}
