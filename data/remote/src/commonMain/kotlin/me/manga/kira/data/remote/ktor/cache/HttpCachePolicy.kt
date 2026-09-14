package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parseHeaderValue

internal const val CACHE_RECORD_PREFIX_BYTES = 12L
private const val MEBIBYTE = 1024 * 1024
private const val KIBIBYTE = 1024
private const val DEFAULT_MAX_TOTAL_BYTES = 16L * MEBIBYTE
private const val DEFAULT_MAX_BODY_BYTES = 4 * MEBIBYTE
private const val DEFAULT_MAX_METADATA_BYTES = 64 * KIBIBYTE

/** Limits cover both namespaces together, not a fresh allowance for every URL or storage view. */
internal data class HttpCachePolicy(
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    val maxMetadataBytes: Int = DEFAULT_MAX_METADATA_BYTES,
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

    fun accepts(
        data: CachedResponseData,
        nowMillis: Long,
    ): Boolean =
        when {
            !data.statusCode.isSuccess() ||
                data.body.size > maxBodyBytes ||
                data.expires.timestamp <= nowMillis -> false
            data.varyKeys.keys.any { it == "*" } -> false
            data.headers
                .getAll(HttpHeaders.Vary)
                .orEmpty()
                .any { value -> value.split(',').any { it.trim() == "*" } } -> false
            else -> {
                val directives = parseHeaderValue(data.headers.getAll(HttpHeaders.CacheControl)?.joinToString(","))
                if (directives.any { it.value.equals("no-store", ignoreCase = true) }) {
                    false
                } else {
                    val explicitFreshness =
                        directives.any {
                            it.value.substringBefore('=').equals("max-age", ignoreCase = true) &&
                                (it.value.substringAfter('=', "").toLongOrNull() ?: 0) > 0
                        } ||
                            data.headers.contains(HttpHeaders.Expires)
                    explicitFreshness && isMetadata(data.headers[HttpHeaders.ContentType])
                }
            }
        }

    private fun isMetadata(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return if (type.startsWith("text/")) {
            type != "text/event-stream"
        } else {
            type == "application/json" ||
                type == "application/xml" ||
                type == "application/javascript" ||
                (type.startsWith("application/") && (type.endsWith("+json") || type.endsWith("+xml")))
        }
    }
}

internal enum class CacheNamespace(
    val directory: String,
) {
    PUBLIC("public"),
    PRIVATE("private"),
}
