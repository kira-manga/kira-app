package me.manga.kira.data.remote.complaint

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidComplaintSessionEngineTest {
    @Test
    fun invalidFactoryTargetsDoNotReturnAnEngineOwner() {
        val invalid =
            listOf(
                "http://example.invalid/api/v1/installations/session",
                "https://example.invalid/api/v1/installations/session?extra=1",
                "https://example.invalid/api/v1/installations/session#extra",
                "https://example.invalid/api/v1/installations/session/extra",
            )
        invalid.forEach { value ->
            val owner = createAndroidComplaintSessionEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
    }

    @Test
    fun nativeIpv6CanonicalizationPreservesOnlyValidatedSessionTargets() {
        val path = "/api/v1/installations/session"
        val spellings =
            mapOf(
                "[0:0:0:0:0:0:0:1]" to "[::1]",
                "[2001:0DB8:0000:0000:0000:0000:0000:0001]" to "[2001:db8::1]",
                "[::ffff:192.0.2.1]" to "192.0.2.1",
            )
        spellings.forEach { (rawHost, canonicalHost) ->
            val configured = Url("https://$rawHost$path")
            val nativeUrl = configured.toString().toHttpUrl().toString()
            val target = assertNotNull(androidComplaintSessionTarget(configured))
            assertEquals("https://$canonicalHost$path", nativeUrl)
            assertTrue(target.matches(nativeUrl))
            assertFalse(target.matches("https://[::2]$path"))
        }
        assertNull(androidComplaintSessionTarget(Url("https://[::1]/bad/..$path")))
    }

    @Test
    fun realSessionPostsDoNotReplayOrFollowRedirects() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                val terminalStatuses =
                    listOf(
                        HttpStatusCode.ServiceUnavailable.value,
                        HttpStatusCode.RequestTimeout.value,
                        HttpStatusCode.Unauthorized.value,
                        HttpStatusCode.SeeOther.value,
                        HttpStatusCode.TemporaryRedirect.value,
                    )
                terminalStatuses.forEachIndexed { index, status ->
                    fixture.server.enqueue(
                        MockResponse
                            .Builder()
                            .code(status)
                            .addHeader("Retry-After", "0")
                            .addHeader("WWW-Authenticate", "Basic realm=\"test\"")
                            .addHeader("Location", fixture.server.url("/forbidden"))
                            .body("terminal")
                            .build(),
                    )
                    fixture.server.enqueue(MockResponse(body = "next"))
                    assertEquals(status, fixture.exchange().status)
                    assertEquals(index * 2 + 1, fixture.server.requestCount)
                    assertEquals("next", fixture.exchange().body)
                    assertEquals(index * 2 + 2, fixture.server.requestCount)
                }
            }
        }

    @Test
    fun nativeCookiesAndCacheDoNotSupplyAnotherSessionResponse() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Cache-Control", "public, max-age=3600")
                        .addHeader("Set-Cookie", "session=test-only; Path=/; Secure")
                        .body("first")
                        .build(),
                )
                fixture.server.enqueue(MockResponse(body = "second"))
                assertEquals("first", fixture.exchange().body)
                assertEquals("second", fixture.exchange().body)
                repeat(2) {
                    val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertNull(request.headers["Cookie"])
                    assertNull(request.headers["Authorization"])
                    assertEquals("identity", request.headers["Accept-Encoding"])
                }
                assertEquals(2, fixture.server.requestCount)
            }
        }

    @Test
    fun changedTargetIsRefusedBeforeAnyHttpRequest() =
        runBlocking {
            AndroidSessionEngineFixture().use { fixture ->
                val changed = Url(fixture.server.url("/forbidden").toString())
                assertFails { fixture.exchange(changed) }
                assertEquals(0, fixture.server.requestCount)
                fixture.server.enqueue(MockResponse(body = "allowed"))
                assertEquals("allowed", fixture.exchange().body)
                assertEquals(1, fixture.server.requestCount)
            }
        }

    @Test
    fun fixedNativePolicySetsFlagsAndRemovesOtherAuthenticationAndInterceptors() {
        val resources = AndroidComplaintSessionResources()
        try {
            val target =
                assertNotNull(
                    androidComplaintSessionTarget(Url("https://example.invalid/api/v1/installations/session")),
                )
            val native =
                OkHttpClient
                    .Builder()
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .authenticator { _, _ -> error("Unexpected authenticator") }
                    .proxyAuthenticator { _, _ -> error("Unexpected proxy authenticator") }
                    .addInterceptor { error("Unexpected application interceptor") }
                    .addNetworkInterceptor { error("Unexpected network interceptor") }
                    .complaintSessionPolicy(target, resources)
                    .build()
            assertFalse(native.retryOnConnectionFailure)
            assertFalse(native.followRedirects)
            assertFalse(native.followSslRedirects)
            assertNull(native.cache)
            assertSame(CookieJar.NO_COOKIES, native.cookieJar)
            assertSame(Authenticator.NONE, native.authenticator)
            assertSame(Authenticator.NONE, native.proxyAuthenticator)
            assertEquals(1, native.interceptors.size)
            assertTrue(native.interceptors.single() is AndroidComplaintSessionInterceptor)
            assertTrue(native.networkInterceptors.isEmpty())
        } finally {
            resources.close()
        }
    }
}
