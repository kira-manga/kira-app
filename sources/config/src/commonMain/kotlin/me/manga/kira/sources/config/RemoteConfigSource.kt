package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.SignedConfigDocument

/**
 * A fetched, still-untrusted remote config artifact: the raw document plus its detached signature
 * metadata. [RemoteSourceConfigManager] verifies the exact payload and signed metadata BEFORE parsing
 * or trusting it. Production wires a bounded HTTPS implementation; an empty release base
 * URL makes that implementation a fail-closed no-op while preserving the bundled fallback.
 */
typealias RemoteConfigPayload = SignedConfigDocument

/**
 * Origin of remote config updates. Implementations return the exact response bytes and detached
 * signature metadata, or null when no newer document is available.
 */
fun interface RemoteConfigSource {
    /** Fetch the latest signed payload, or null if nothing newer/available. May throw on I/O error. */
    suspend fun fetch(): RemoteConfigPayload?
}
