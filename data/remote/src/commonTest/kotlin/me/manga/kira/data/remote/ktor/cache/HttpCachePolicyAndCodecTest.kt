package me.manga.kira.data.remote.ktor.cache

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpCachePolicyAndCodecTest {
    @Test
    fun onlyExplicitlyFreshMetadataIsEligibleIncludingAndroidDadosPolicy() {
        val policy = HttpCachePolicy()
        listOf("text/html; charset=UTF-8", "application/json", "application/feed+json", "application/atom+xml")
            .forEach { type ->
                val data = cachedResponse(headers = cacheHeaders(type, "public, max-age=86400"))
                assertTrue(policy.accepts(data, CACHE_TEST_NOW))
            }
        val expires =
            Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Expires, "Thu, 01 Jan 1970 00:01:01 GMT")
            }
        assertTrue(policy.accepts(cachedResponse(headers = expires), CACHE_TEST_NOW))
    }

    @Test
    fun headerlessNoStoreImagesBinaryAndEventStreamsAreNotRetained() {
        val policy = HttpCachePolicy()
        val rejected =
            listOf(
                cacheHeaders(null, null),
                cacheHeaders(cacheControl = null),
                cacheHeaders(cacheControl = "public, no-store, max-age=60"),
                cacheHeaders(cacheControl = "max-age=0"),
                cacheHeaders("image/jpeg"),
                cacheHeaders("application/octet-stream"),
                cacheHeaders("text/event-stream; charset=utf-8"),
            )
        rejected.forEach { headers -> assertFalse(policy.accepts(cachedResponse(headers = headers), CACHE_TEST_NOW)) }
        assertFalse(policy.accepts(cachedResponse(expires = CACHE_TEST_NOW), CACHE_TEST_NOW))
        assertFalse(policy.accepts(cachedResponse(vary = mapOf("*" to "")), CACHE_TEST_NOW))
        val wildcard =
            Headers.build {
                appendAll(cacheHeaders())
                append(HttpHeaders.Vary, "Accept-Language, *")
            }
        assertFalse(policy.accepts(cachedResponse(headers = wildcard), CACHE_TEST_NOW))
    }

    @Test
    fun codecRoundTripsHeadersDatesVaryAndBodyWithoutEncodingBodyIntoMetadata() {
        val headers =
            Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.CacheControl, "max-age=60")
                append("X-Value", "one")
                append("X-Value", "two")
            }
        val data = cachedResponse(body = ByteArray(400) { 3 }, vary = mapOf("Language" to "العربية"), headers = headers)
        val codec = HttpCacheMetadataCodec(1_024)
        val metadata = assertNotNull(codec.encode(data))
        val decoded = assertNotNull(codec.decode(metadata, data.body))
        assertEquals(data.url, decoded.url)
        assertEquals(data.statusCode, decoded.statusCode)
        assertEquals(data.version, decoded.version)
        assertEquals(data.requestTime, decoded.requestTime)
        assertEquals(data.responseTime, decoded.responseTime)
        assertEquals(data.expires, decoded.expires)
        assertEquals(data.headers.entries(), decoded.headers.entries())
        assertEquals(data.varyKeys, decoded.varyKeys)
        assertContentEquals(data.body, decoded.body)
        val otherBody = cachedResponse(body = ByteArray(10), vary = data.varyKeys, headers = headers)
        assertContentEquals(metadata, assertNotNull(codec.encode(otherBody)))
    }

    @Test
    fun codecRejectsOversizedLengthTruncationAndTrailingBytes() {
        val codec = HttpCacheMetadataCodec(1_024)
        val body = byteArrayOf(1)
        val valid = assertNotNull(codec.encode(cachedResponse(body = body)))
        assertNull(codec.decode(Buffer().writeInt(Int.MAX_VALUE).readByteArray(), body))
        assertNull(codec.decode(valid.copyOf(valid.size - 1), body))
        assertNull(codec.decode(valid + byteArrayOf(0), body))
        assertNull(codec.decode(ByteArray(1_025), body))
        assertNull(codec.decode(byteArrayOf(), body))
    }
}
