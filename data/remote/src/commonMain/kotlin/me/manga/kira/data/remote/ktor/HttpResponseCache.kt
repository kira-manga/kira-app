package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.util.AttributeKey
import me.manga.kira.core.cache.HttpCacheClearer
import me.manga.kira.data.remote.ktor.cache.FileHttpCachePersistence
import me.manga.kira.data.remote.ktor.cache.HttpCachePolicy
import me.manga.kira.data.remote.ktor.cache.ManagedHttpCache
import okio.FileSystem
import okio.Path

private val responseCacheOwnerKey = AttributeKey<HttpCacheClearer>("kira-response-cache-owner")

/**
 * Returns the actual live-and-persistent cache owner attached by [createHttpClient].
 *
 * Throws for an uncached or unrelated client rather than silently binding a no-op clearer.
 * Only the cache-enabled shared client should supply the Settings cache-clear port.
 */
fun HttpClient.responseCacheClearer(): HttpCacheClearer =
    checkNotNull(attributes.getOrNull(responseCacheOwnerKey)) { "This client does not own a response cache" }

internal fun HttpClientConfig<*>.installManagedHttpCache(owner: ManagedHttpCache?) {
    val managed = owner ?: return
    install(HttpCache) {
        publicStorage(managed.publicStorage)
        privateStorage(managed.privateStorage)
    }
    install(HttpCacheRevalidationRecovery) {
        this.owner = managed
    }
}

internal fun HttpClient.attachResponseCache(owner: ManagedHttpCache?): HttpClient =
    apply {
        if (owner != null) attributes.put(responseCacheOwnerKey, owner)
    }

internal fun createPersistentHttpCache(root: Path): ManagedHttpCache {
    val policy = HttpCachePolicy()
    return ManagedHttpCache(policy, FileHttpCachePersistence(FileSystem.SYSTEM, root, policy))
}
