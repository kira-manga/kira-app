package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CacheStorage
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.cache.HttpCacheClearer
import me.manga.kira.core.dispatchers.platformIoDispatcher
import okio.IOException

/**
 * One owner/budget for both Ktor storage views. The charge is body + encoded metadata + framing,
 * not a whole-process heap limit: Ktor's in-flight buffering and object overhead are separate.
 * Expiry is swept on every operation (including startup), without an unowned background worker.
 */
internal class ManagedHttpCache(
    private val policy: HttpCachePolicy = HttpCachePolicy(),
    private val persistence: HttpCachePersistence? = null,
    private val nowMillis: () -> Long = { GMTDate().timestamp },
    private val dispatcher: CoroutineDispatcher = platformIoDispatcher,
) : HttpCacheClearer {
    private val mutex = Mutex()
    private val codec = HttpCacheMetadataCodec(policy.maxMetadataBytes)
    private val entries = LinkedHashMap<CacheKey, CacheEntry>()
    private var totalBytes = 0L
    private var loaded = false
    private var disabled = false

    val publicStorage: CacheStorage = Storage(CacheNamespace.PUBLIC)
    val privateStorage: CacheStorage = Storage(CacheNamespace.PRIVATE)

    override suspend fun clear() = withContext(dispatcher) {
        mutex.withLock {
            // A failed disk clear must not leave live hits or allow repeated failing writes to grow disk.
            discardLiveEntries()
            disabled = true
            persistence?.clear()
            loaded = true
            disabled = false
        }
    }

    internal suspend fun snapshot(): HttpCacheSnapshot = operation(HttpCacheSnapshot(0, 0, 0)) {
        HttpCacheSnapshot(
            entries.size,
            totalBytes,
            entries.keys.groupingBy { it.url }.eachCount().values.maxOrNull() ?: 0,
        )
    }

    private suspend fun store(namespace: CacheNamespace, url: Url, data: CachedResponseData) = operation(Unit) {
        if (url != data.url) return@operation
        val metadata = codec.encode(data) ?: return@operation
        if (!policy.accepts(data, nowMillis())) return@operation
        val key = CacheKey(namespace, url, data.varyKeys.toMap())
        val charge = recordBytes(data, metadata)
        if (!makeRoom(key, charge)) return@operation
        // Reconstruct bounded metadata too: retain no caller-owned mutable header/Vary collection.
        val owned = codec.decode(metadata, data.body.copyOf()) ?: return@operation
        if (owned.url != url || owned.varyKeys != key.varyKeys) return@operation
        persistence?.write(namespace, owned, metadata)
        retain(key, owned, charge)
    }

    private fun restore(namespace: CacheNamespace, data: CachedResponseData, metadata: ByteArray) {
        val key = CacheKey(namespace, data.url, data.varyKeys.toMap())
        val charge = recordBytes(data, metadata)
        if (!policy.accepts(data, nowMillis()) || metadata.size > policy.maxMetadataBytes) {
            persistence?.remove(namespace, data.url, data.varyKeys)
        } else if (makeRoom(key, charge)) {
            retain(key, data, charge)
        } else {
            persistence?.remove(namespace, data.url, data.varyKeys)
        }
    }

    private fun makeRoom(key: CacheKey, charge: Long): Boolean {
        if (charge > policy.maxTotalBytes) return false
        removeEntry(key)
        while (entries.keys.count { it.url == key.url } >= policy.maxVariantsPerUrl) {
            removeEntry(entries.keys.first { it.url == key.url })
        }
        while (entries.size >= policy.maxEntries || totalBytes > policy.maxTotalBytes - charge) {
            removeEntry(entries.keys.first())
        }
        return true
    }

    private fun retain(key: CacheKey, data: CachedResponseData, charge: Long) {
        entries[key] = CacheEntry(data, charge)
        totalBytes += charge
    }

    private fun removeEntry(key: CacheKey) {
        val entry = entries[key] ?: return
        persistence?.remove(key.namespace, key.url, key.varyKeys)
        entries.remove(key)
        totalBytes -= entry.bytes
    }

    private fun touch(key: CacheKey): CachedResponseData? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry.data
    }

    private fun sweepExpired() {
        val now = nowMillis()
        entries.filterValues { it.data.expires.timestamp <= now }.keys.forEach(::removeEntry)
    }

    private fun loadIfNeeded() {
        if (loaded) return
        persistence?.load(::restore)
        loaded = true
    }

    private fun discardLiveEntries() {
        entries.clear()
        totalBytes = 0
    }

    private suspend fun <T> operation(miss: T, block: () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            if (disabled) return@withLock miss
            try {
                loadIfNeeded()
                sweepExpired()
                block()
            } catch (_: IOException) {
                // The cache is optional. Stop all further I/O/growth until an explicit successful clear.
                // Do not catch cancellation, programming errors, or return success from clear().
                discardLiveEntries()
                disabled = true
                miss
            }
        }
    }

    private inner class Storage(private val namespace: CacheNamespace) : CacheStorage {
        override suspend fun store(url: Url, data: CachedResponseData) {
            this@ManagedHttpCache.store(namespace, url, data)
        }

        override suspend fun find(url: Url, varyKeys: Map<String, String>): CachedResponseData? =
            operation(null) { touch(CacheKey(namespace, url, varyKeys)) }

        override suspend fun findAll(url: Url): Set<CachedResponseData> = operation(emptySet()) {
            val keys = entries.keys.filter { it.namespace == namespace && it.url == url }
            keys.mapNotNull(::touch).toSet()
        }

        override suspend fun remove(url: Url, varyKeys: Map<String, String>) = operation(Unit) {
            removeEntry(CacheKey(namespace, url, varyKeys))
        }

        override suspend fun removeAll(url: Url) = operation(Unit) {
            entries.keys.filter { it.namespace == namespace && it.url == url }.forEach(::removeEntry)
        }
    }
}

private data class CacheKey(val namespace: CacheNamespace, val url: Url, val varyKeys: Map<String, String>)
private data class CacheEntry(val data: CachedResponseData, val bytes: Long)
internal data class HttpCacheSnapshot(val entries: Int, val bytes: Long, val mostVariantsPerUrl: Int)

private fun recordBytes(data: CachedResponseData, metadata: ByteArray): Long =
    CACHE_RECORD_PREFIX_BYTES + data.body.size + metadata.size
