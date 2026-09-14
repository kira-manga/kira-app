package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import okio.Buffer
import okio.IOException
import okio.utf8Size

/** Length-prefixed metadata; body bytes are streamed separately into the bounded disk record. */
internal class HttpCacheMetadataCodec(private val maxBytes: Int) {
    fun encode(data: CachedResponseData): ByteArray? = try {
        val writer = MetadataWriter(maxBytes)
        writer.string(data.url.toString())
        writer.int(data.statusCode.value)
        writer.string(data.statusCode.description)
        writer.string(data.version.toString())
        writer.long(data.requestTime.timestamp)
        writer.long(data.responseTime.timestamp)
        writer.long(data.expires.timestamp)
        writer.headers(data.headers)
        writer.varyKeys(data.varyKeys)
        writer.finish()
    } catch (_: MetadataLimitExceeded) {
        null
    }

    fun decode(metadata: ByteArray, body: ByteArray): CachedResponseData? {
        if (metadata.size > maxBytes) return null
        return try {
            decodeEntry(MetadataReader(metadata), body)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun decodeEntry(reader: MetadataReader, body: ByteArray): CachedResponseData {
        val url = Url(reader.string())
        val status = HttpStatusCode(reader.int(), reader.string())
        val version = HttpProtocolVersion.parse(reader.string())
        val requested = GMTDate(reader.long())
        val received = GMTDate(reader.long())
        val expires = GMTDate(reader.long())
        val headers = reader.headers()
        val vary = reader.varyKeys()
        require(reader.exhausted())
        return CachedResponseData(url, status, requested, received, version, expires, headers, vary, body)
    }
}

private class MetadataLimitExceeded : IllegalArgumentException()

private class MetadataWriter(private val limit: Int) {
    private val buffer = Buffer()

    fun int(value: Int) {
        admit(Int.SIZE_BYTES.toLong())
        buffer.writeInt(value)
    }

    fun long(value: Long) {
        admit(Long.SIZE_BYTES.toLong())
        buffer.writeLong(value)
    }

    fun string(value: String) {
        val size = value.utf8Size()
        admit(Int.SIZE_BYTES + size)
        buffer.writeInt(size.toInt())
        buffer.writeUtf8(value)
    }

    fun headers(headers: Headers) {
        val count = headers.entries().sumOf { it.value.size.toLong() }
        if (count > limit / (2 * Int.SIZE_BYTES)) throw MetadataLimitExceeded()
        int(count.toInt())
        headers.entries().forEach { (key, values) ->
            values.forEach { value ->
                string(key)
                string(value)
            }
        }
    }

    fun varyKeys(keys: Map<String, String>) {
        if (keys.size > limit / (2 * Int.SIZE_BYTES)) throw MetadataLimitExceeded()
        int(keys.size)
        keys.entries.sortedBy { it.key }.forEach { (key, value) ->
            string(key)
            string(value)
        }
    }

    fun finish(): ByteArray = buffer.readByteArray()

    private fun admit(size: Long) {
        if (size > limit - buffer.size) throw MetadataLimitExceeded()
    }
}

private class MetadataReader(metadata: ByteArray) {
    private val buffer = Buffer().write(metadata)

    fun int(): Int = buffer.readInt()
    fun long(): Long = buffer.readLong()
    fun exhausted(): Boolean = buffer.exhausted()

    fun string(): String {
        val size = int()
        require(size >= 0 && size.toLong() <= buffer.size)
        return buffer.readUtf8(size.toLong())
    }

    fun headers(): Headers {
        val builder = HeadersBuilder()
        repeat(pairCount()) { builder.append(string(), string()) }
        return builder.build()
    }

    fun varyKeys(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        repeat(pairCount()) {
            val key = string()
            require(key !in result)
            result[key] = string()
        }
        return result
    }

    private fun pairCount(): Int {
        val count = int()
        require(count >= 0 && count.toLong() <= buffer.size / (2 * Int.SIZE_BYTES))
        return count
    }
}
