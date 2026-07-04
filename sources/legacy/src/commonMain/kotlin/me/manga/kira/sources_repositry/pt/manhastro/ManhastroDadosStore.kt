package me.manga.kira.sources_repositry.pt.manhastro

/**
 * Migration note (Phase 7.7): Retrofit -> Ktor ApiClient, jsoup -> ksoup, FormBody -> Map,
 * Gson -> kotlinx.serialization, @Inject dropped, android.util.Log -> Kermit Logger,
 * java.time -> kotlinx.datetime.
 *
 * Concurrency: the Android source used `@Synchronized` (a JVM monitor) for the in-memory map.
 * `@Synchronized` is JVM-only and not available in commonMain, so we replace it with a
 * `kotlinx.atomicfu.locks.SynchronizedObject` + `synchronized { ... }`, which gives equivalent
 * mutual-exclusion semantics across JVM, Native and JS targets.
 */

import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.manga.kira.sources_repositry.pt.manhastro.models.home.Data
import me.manga.kira.sources_repositry.pt.manhastro.models.home.manhastorHomeRespone

class ManhastroDadosStore {

    private val lock = SynchronizedObject()
    private val map = mutableMapOf<Int, Data>()

    fun save(response: manhastorHomeRespone) {
        synchronized(lock) {
            response.data
                ?.filterNotNull()
                ?.forEach { item ->
                    item.manga_id?.let { id ->
                        map[id] = item
                    }
                }
        }
    }

    fun get(mangaId: Int): Data? = synchronized(lock) { map[mangaId] }

    fun search(query: String): List<Data> = synchronized(lock) {
        if (query.isBlank()) return@synchronized emptyList()

        val q = query.trim().lowercase()

        map.values
            .filter { data ->
                val t = data.titulo?.lowercase().orEmpty()
                val tb = data.titulo_brasil?.lowercase().orEmpty()
                t.contains(q) || tb.contains(q)
            }
            .sortedBy { data ->
                val t = data.titulo?.lowercase().orEmpty()
                val tb = data.titulo_brasil?.lowercase().orEmpty()
                minOf(
                    t.indexOf(q).takeIf { it >= 0 } ?: Int.MAX_VALUE,
                    tb.indexOf(q).takeIf { it >= 0 } ?: Int.MAX_VALUE
                )
            }
    }

    fun clear() {
        synchronized(lock) {
            if (map.isNotEmpty()) {
                Logger.withTag("Asfdafsdfdfdsfsdfsdf").i { "clear map " }
                map.clear()
            }
        }
    }
}
