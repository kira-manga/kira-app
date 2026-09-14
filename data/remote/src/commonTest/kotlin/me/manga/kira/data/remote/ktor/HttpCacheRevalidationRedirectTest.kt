package me.manga.kira.data.remote.ktor

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.remote.ktor.cache.assertWithin
import me.manga.kira.data.remote.ktor.cache.cacheOwner
import me.manga.kira.data.remote.ktor.cache.smallCachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val REDIRECT_ORIGIN_URL = "https://origin.test/start"
private const val REDIRECT_TARGET_URL = "https://redirected.test/catalog"

class HttpCacheRevalidationRedirectTest {
    @Test
    fun recoveryUsesTheFailedHopAndDoesNotRestoreRedirectStrippedCredentials() =
        runTest {
            val replies = RedirectedRevalidation(this)
            val cookiePolicy = redirectedCookiePolicy()
            withHttpCacheClient(replies.cache, replies.handler, configure = { install(cookiePolicy) }) { client ->
                assertEquals(
                    OLD_METADATA_BODY,
                    client
                        .get(REDIRECT_TARGET_URL) {
                            headers.append("X-Edition", "phone")
                        }.bodyAsText(),
                )
                assertEquals(NEW_METADATA_BODY, client.fetchRevalidationMetadata(REDIRECT_ORIGIN_URL))
                assertEquals(1, replies.originCalls)
                assertEquals(3, replies.targetCalls)
                assertEquals(0, replies.cache.snapshot().entries)
                replies.cache.assertWithin(smallCachePolicy())
            }
        }

    @Test
    fun redirectCopiesCannotObtainASecondRecoveryBudget() =
        runTest {
            val replies = RedirectBudgetReplies(this)
            withHttpCacheClient(replies.cache, replies.handler) { client ->
                assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata(REDIRECT_ORIGIN_URL))
                assertEquals(OLD_METADATA_BODY, client.fetchRevalidationMetadata(REDIRECT_TARGET_URL))
                assertFailsWith<InvalidCacheStateException> { client.fetchRevalidationMetadata(REDIRECT_ORIGIN_URL) }
                assertEquals(3, replies.originCalls) // prime, conditional304, sole repair (redirect)
                assertEquals(2, replies.targetCalls) // prime, redirected conditional304; no second repair
                assertEquals(0, replies.cache.snapshot().entries)
                replies.cache.assertWithin(smallCachePolicy())
            }
        }
}

private class RedirectedRevalidation(
    scope: TestScope,
) {
    val cache = scope.cacheOwner()
    var originCalls = 0
    var targetCalls = 0
    val handler: MockRequestHandler = { request ->
        if (request.url == Url(REDIRECT_ORIGIN_URL)) {
            originCalls++
            request.assertOriginalMetadataHeaders()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, REDIRECT_TARGET_URL))
        } else {
            assertEquals(Url(REDIRECT_TARGET_URL), request.url)
            when (++targetCalls) {
                1 -> respond(OLD_METADATA_BODY, headers = revalidationHeaders())
                2 -> {
                    request.assertRedirectedHeaders()
                    request.assertCacheConditional()
                    cache.publicStorage.removeAll(request.url)
                    respond("", HttpStatusCode.NotModified, revalidationHeaders())
                }
                3 -> {
                    request.assertRedirectedHeaders()
                    request.assertUnconditional()
                    respond(NEW_METADATA_BODY, headers = revalidationHeaders())
                }
                else -> error("Unexpected redirected request")
            }
        }
    }
}

private fun HttpRequestData.assertRedirectedHeaders() {
    assertNull(headers[HttpHeaders.Authorization]) // Ktor's cross-origin redirect stripping must survive the repair.
    assertNull(headers[HttpHeaders.Cookie]) // The installed host-specific policy must survive too.
    assertEquals(revalidationRequestHeaders[HttpHeaders.UserAgent], headers[HttpHeaders.UserAgent])
    assertEquals(revalidationRequestHeaders[HttpHeaders.Referrer], headers[HttpHeaders.Referrer])
    assertEquals("phone", headers["X-Edition"])
}

private fun redirectedCookiePolicy() =
    createClientPlugin("RedirectedCookiePolicy") {
        on(SendingRequest) { request, _ ->
            if (Url(request.url) == Url(REDIRECT_TARGET_URL)) request.headers.remove(HttpHeaders.Cookie)
        }
    }

private class RedirectBudgetReplies(
    scope: TestScope,
) {
    val cache = scope.cacheOwner()
    var originCalls = 0
    var targetCalls = 0
    val handler: MockRequestHandler = { request ->
        val origin = request.url == Url(REDIRECT_ORIGIN_URL)
        assertEquals(Url(if (origin) REDIRECT_ORIGIN_URL else REDIRECT_TARGET_URL), request.url)
        val calls = if (origin) ++originCalls else ++targetCalls
        when (calls) {
            1 -> respond(OLD_METADATA_BODY, headers = revalidationHeaders())
            2 -> {
                request.assertCacheConditional()
                cache.publicStorage.removeAll(request.url)
                respond("", HttpStatusCode.NotModified, revalidationHeaders())
            }
            3 -> {
                check(origin) { "Redirect incorrectly reset the recovery budget" }
                request.assertUnconditional()
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, REDIRECT_TARGET_URL))
            }
            else -> error("Unexpected redirect lineage request")
        }
    }
}
