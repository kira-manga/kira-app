package me.manga.kira.data.local.entity

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Original Room snapshot plus the exact current-owner loose filename, never an absolute delete target. */
@Serializable
data class ChapterConversionSource(val storedPath: String, val relativePath: String)

/** One strict, nullable TEXT payload in the existing custody row. Invalid payloads retain custody. */
object ChapterConversionRoster {
    private val serializer = ListSerializer(ChapterConversionSource.serializer())

    fun encode(sources: List<ChapterConversionSource>): String {
        validate(sources)
        return Json.encodeToString(serializer, sources)
    }

    fun decode(payload: String): List<ChapterConversionSource> =
        Json.decodeFromString(serializer, payload).also(::validate)

    private fun validate(sources: List<ChapterConversionSource>) {
        require(sources.isNotEmpty())
        require(sources.map { it.relativePath }.distinct().size == sources.size)
        require(sources.all {
            it.storedPath.isNotBlank() && it.relativePath.isNotBlank() &&
                !it.relativePath.startsWith('.') && '/' !in it.relativePath && '\\' !in it.relativePath &&
                '\u0000' !in it.relativePath && !it.relativePath.endsWith(".cbz", ignoreCase = true)
        })
    }
}
