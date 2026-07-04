package me.manga.yamiapk.sources_repositry.pt.manhastro

import android.util.Log
import jakarta.inject.Inject
import jakarta.inject.Singleton
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.home.Data
import me.manga.yamiapk.sources_repositry.pt.manhastro.models.home.manhastorHomeRespone

@Singleton
class ManhastroDadosStore @Inject constructor() {

    private val map = mutableMapOf<Int, Data>()

    @Synchronized
    fun save(response: manhastorHomeRespone) {
        response.data
            ?.filterNotNull()
            ?.forEach { item ->
                item.manga_id?.let { id ->
                    map[id] = item
                }
            }
    }

    @Synchronized
    fun get(mangaId: Int): Data? = map[mangaId]

    @Synchronized
    fun search(query: String): List<Data> {
        if (query.isBlank()) return emptyList()

        val q = query.trim().lowercase()

        return map.values
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

    @Synchronized
    fun clear() {
        if (!map.isEmpty()) {
            Log.i("Asfdafsdfdfdsfsdfsdf","clear map ")
            map.clear()
        }

    }
}
