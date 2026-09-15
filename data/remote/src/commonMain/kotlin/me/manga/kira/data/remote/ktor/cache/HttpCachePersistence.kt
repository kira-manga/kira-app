package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Url

/** Called only while the owning cache holds its single mutex on its I/O dispatcher. */
internal interface HttpCachePersistence {
    fun load(consume: (CacheNamespace, CachedResponseData, ByteArray) -> Unit)

    fun write(
        namespace: CacheNamespace,
        data: CachedResponseData,
        metadata: ByteArray,
    )

    fun remove(
        namespace: CacheNamespace,
        url: Url,
        varyKeys: Map<String, String>,
    )

    fun clear()
}
