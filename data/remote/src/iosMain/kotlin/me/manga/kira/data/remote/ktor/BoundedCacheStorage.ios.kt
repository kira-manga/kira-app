package me.manga.kira.data.remote.ktor

import io.ktor.client.plugins.cache.storage.CacheStorage
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded in-memory [CacheStorage] for the iOS singleton HttpClient.
 *
 * Ktor 3.4 ships no disk-backed `FileStorage` on Kotlin/Native (it is JVM-only), so the iOS HTTP
 * cache cannot live on disk the way the Android/Desktop factories do. The default
 * `CacheStorage.Unlimited()` is an unbounded in-memory map that retains every cacheable response
 * body — including multi-MB manga page images served with long `max-age` headers — for the whole
 * process lifetime with no eviction. On iOS that grows the heap without bound and lets jetsam
 * terminate the app under memory pressure during a long download/scrape session.
 *
 * This storage caps the number of retained URL keys at [maxEntries] and evicts the
 * least-recently-stored entry (insertion-ordered FIFO) once the cap is exceeded, so the cache's
 * memory footprint stays bounded while still honoring server `Cache-Control` for hot, recent
 * requests. The cap is intentionally small because cached bodies on the image path are large.
 */
internal class BoundedCacheStorage(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : CacheStorage {

    private val mutex = Mutex()
    // Insertion-ordered so the oldest key is the first to evict once the cap is exceeded.
    private val store = LinkedHashMap<Url, MutableSet<CachedResponseData>>()

    override suspend fun store(url: Url, data: CachedResponseData) {
        mutex.withLock {
            val cache = store.getOrPut(url) { mutableSetOf() }
            if (!cache.add(data)) {
                cache.remove(data)
                cache.add(data)
            }
            while (store.size > maxEntries) {
                val oldest = store.keys.firstOrNull() ?: break
                store.remove(oldest)
            }
        }
    }

    override suspend fun find(url: Url, varyKeys: Map<String, String>): CachedResponseData? =
        mutex.withLock {
            store[url]?.find { entry ->
                varyKeys.all { (key, value) -> entry.varyKeys[key] == value }
            }
        }

    override suspend fun findAll(url: Url): Set<CachedResponseData> =
        mutex.withLock { store[url]?.toSet() ?: emptySet() }

    override suspend fun remove(url: Url, varyKeys: Map<String, String>) {
        mutex.withLock {
            store[url]?.removeAll { entry ->
                varyKeys.all { (key, value) -> entry.varyKeys[key] == value } &&
                    varyKeys.size == entry.varyKeys.size
            }
        }
    }

    override suspend fun removeAll(url: Url) {
        mutex.withLock { store.remove(url) }
    }

    private companion object {
        private const val DEFAULT_MAX_ENTRIES = 64
    }
}
