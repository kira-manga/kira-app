package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parseHeaderValue

internal const val CACHE_RECORD_PREFIX_BYTES = 12L
private const val MEBIBYTE = 1024 * 1024
private const val KIBIBYTE = 1024

/** Limits cover both namespaces together, not a fresh allowance for every URL or storage view. */
internal data class HttpCachePolicy(
    val maxTotalBytes: Long = 16L * MEBIBYTE,
    val maxBodyBytes: Int = 4 * MEBIBYTE,
    val maxMetadataBytes: Int = 64 * KIBIBYTE,
    val maxEntries: Int = 128,
    val maxVariantsPerUrl: Int = 4,
) {
    init {
        require(maxTotalBytes > 0)
        require(maxBodyBytes > 0)
        require(maxMetadataBytes > 0)
        require(maxEntries > 0)
        require(maxVariantsPerUrl in 1..maxEntries)
    }

    fun accepts(data: CachedResponseData, nowMillis: Long): Boolean {
        if (!data.statusCode.isSuccess() || data.body.size > maxBodyBytes || data.expires.timestamp <= nowMillis) return false
        if (data.varyKeys.keys.any { it == "*" }) return false
        if (data.headers.getAll(HttpHeaders.Vary).orEmpty().any { value -> value.split(',').any { it.trim() == "*" } }) {
            return false
        }
        val directives = parseHeaderValue(data.headers.getAll(HttpHeaders.CacheControl)?.joinToString(","))
        if (directives.any { it.value.equals("no-store", ignoreCase = true) }) return false
        val explicitFreshness = directives.any {
            it.value.substringBefore('=').equals("max-age", ignoreCase = true) &&
                (it.value.substringAfter('=', "").toLongOrNull() ?: 0) > 0
        } || data.headers.contains(HttpHeaders.Expires)
        return explicitFreshness && isMetadata(data.headers[HttpHeaders.ContentType])
    }

    private fun isMetadata(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        if (type.startsWith("text/")) return type != "text/event-stream"
        return type == "application/json" || type == "application/xml" || type == "application/javascript" ||
            (type.startsWith("application/") && (type.endsWith("+json") || type.endsWith("+xml")))
    }
}

internal enum class CacheNamespace(val directory: String) {
    PUBLIC("public"),
    PRIVATE("private"),
}
