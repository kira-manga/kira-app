package me.manga.kira.data.remote.complaint

import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidComplaintEnrollmentEngineTest {
    @Test
    fun enrollmentAndSessionFactoriesKeepSeparateClosedTargets() {
        val invalid =
            listOf(
                "http://example.invalid/api/v1/installations",
                "https://example.invalid/api/v1/installations/bootstrap",
                "https://example.invalid/api/v1/installations/session",
                "https://example.invalid/api/v1/installations/",
                "https://example.invalid/api/v1/other-installations",
                "https://example.invalid/api/v1/installations?extra=1",
                "https://example.invalid/api/v1/installations?",
                "https://example.invalid/api/v1/installations#extra",
                "https://user:synthetic@example.invalid/api/v1/installations",
                "https://example.invalid/bad/../api/v1/installations",
                "https://example.invalid/bad/%2e%2e/api/v1/installations",
                "https://example.invalid//api/v1/installations",
            )
        invalid.forEach { assertNoOwner(createAndroidComplaintEnrollmentEngineOwner(Url(it))) }
        listOf("", "/bootstrap").forEach { suffix ->
            assertNoOwner(
                createAndroidComplaintSessionEngineOwner(Url("https://example.invalid/api/v1/installations$suffix")),
            )
        }
    }

    @Test
    fun validatedPrefixAndIpv6CanonicalizationCannotChangeTheTwoRoutes() {
        val path = "/base_1/v2/api/v1/installations"
        val target = assertNotNull(androidComplaintEnrollmentTarget(Url("https://[0:0:0:0:0:0:0:1]:8443$path")))
        assertTrue(target.matchesEnrollment("https://[::1]:8443$path"))
        assertTrue(target.matchesBootstrap("https://[::1]:8443$path/bootstrap"))
        assertFalse(target.matchesEnrollment("https://[::1]:8443$path/session"))
        assertFalse(target.matchesBootstrap("https://[::1]$path/bootstrap"))
        assertFalse(target.matchesBootstrap("https://[::2]:8443$path/bootstrap"))
        assertFalse(target.matchesBootstrap("https://[::1]:8443/api/v1/installations/bootstrap"))
        assertFalse(target.matchesBootstrap("https://[::1]:8443$path/bootstrap?extra=1"))
        assertNull(androidComplaintEnrollmentTarget(Url("https://[::1]/bad/..$path")))
        assertEquals("ComplaintEnrollmentTarget(redacted)", target.toString())
    }

    @Test
    fun realBootstrapAndEnrollmentUseExactRoutesWithoutCookiesOrNativeCache() =
        runBlocking {
            AndroidSessionEngineFixture(enrollment = true, basePath = "/base_1/v2").use { fixture ->
                fixture.server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Cache-Control", "public, max-age=3600")
                        .addHeader("Set-Cookie", "bootstrap=test-only; Path=/; Secure")
                        .body("first")
                        .build(),
                )
                fixture.server.enqueue(MockResponse(code = HttpStatusCode.Created.value, body = "enrolled"))
                fixture.server.enqueue(MockResponse(body = "fresh"))
                assertEquals("first", fixture.bootstrap().body)
                assertEquals(HttpStatusCode.Created.value, fixture.enroll().status)
                assertEquals("fresh", fixture.bootstrap().body)
                assertExactRequests(fixture)
            }
        }

    @Test
    fun enrollmentPolicyRetainsFixedIsolationAndOnlyItsTwoGuards() {
        val resources = AndroidComplaintSessionResources()
        try {
            val target =
                assertNotNull(androidComplaintEnrollmentTarget(Url("https://example.invalid/api/v1/installations")))
            val native =
                OkHttpClient
                    .Builder()
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .authenticator { _, _ -> error("Unexpected authenticator") }
                    .proxyAuthenticator { _, _ -> error("Unexpected proxy authenticator") }
                    .addInterceptor { error("Unexpected application interceptor") }
                    .addNetworkInterceptor { error("Unexpected network interceptor") }
                    .complaintEnrollmentPolicy(target, resources)
                    .build()
            assertFixedIsolation(native, resources)
        } finally {
            resources.close()
        }
    }

    private fun assertNoOwner(owner: ComplaintSessionEngineOwner?) {
        try {
            assertNull(owner)
        } finally {
            owner?.close()
        }
    }

    private fun assertFixedIsolation(
        native: OkHttpClient,
        resources: AndroidComplaintSessionResources,
    ) {
        assertFalse(native.retryOnConnectionFailure)
        assertFalse(native.followRedirects)
        assertFalse(native.followSslRedirects)
        assertNull(native.cache)
        assertSame(CookieJar.NO_COOKIES, native.cookieJar)
        assertSame(Authenticator.NONE, native.authenticator)
        assertSame(Authenticator.NONE, native.proxyAuthenticator)
        assertSame(resources.dispatcher, native.dispatcher)
        assertSame(resources.pool, native.connectionPool)
        assertTrue(native.interceptors.single() is AndroidComplaintEnrollmentInterceptor)
        assertTrue(native.networkInterceptors.single() is AndroidComplaintBootstrapFollowUpGuard)
    }

    private fun assertExactRequests(fixture: AndroidSessionEngineFixture) {
        repeat(EXPECTED_ROUTE_REQUESTS) { index ->
            val request = assertNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
            val enrollment = index == 1
            assertEquals(if (enrollment) "POST" else "GET", request.method)
            assertEquals("/base_1/v2/api/v1/installations" + if (enrollment) "" else "/bootstrap", request.target)
            assertEquals(if (enrollment) ENROLLMENT_TEST_BODY else "", request.body?.utf8().orEmpty())
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertEquals("application/json, application/problem+json", request.headers["Accept"])
            assertEquals("no-store, no-transform", request.headers["Cache-Control"])
            listOf("Cookie", "Authorization", "Proxy-Authorization").forEach { assertNull(request.headers[it]) }
            if (!enrollment) {
                assertEquals(0L, request.bodySize)
                listOf("Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding")
                    .forEach { assertNull(request.headers[it]) }
            }
        }
        assertEquals(EXPECTED_ROUTE_REQUESTS, fixture.server.requestCount)
    }

    private companion object {
        const val EXPECTED_ROUTE_REQUESTS = 3
    }
}
