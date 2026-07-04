package me.manga.kira.sources.config

/**
 * A fetched, still-untrusted remote config artifact: the raw document [payload] plus its detached
 * [signatureBase64]. [RemoteSourceConfigManager] verifies the signature over the payload bytes BEFORE
 * parsing or trusting it. In Stage-0 no [RemoteConfigSource] is wired (remote is disabled), so this
 * type describes the shape without turning the network path on.
 */
data class RemoteConfigPayload(
    val payload: String,
    val signatureBase64: String,
)

/**
 * The (optional) origin of remote config updates. Left null in Stage-0 → the manager never reaches
 * the network and only ever resolves bundled/cache. When enabled later, an implementation lives at
 * the composition root over the app's HTTP client.
 */
fun interface RemoteConfigSource {
    /** Fetch the latest signed payload, or null if nothing newer/available. May throw on I/O error. */
    suspend fun fetch(): RemoteConfigPayload?
}
