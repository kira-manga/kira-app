package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import me.manga.kira.data.remote.ktor.createHttpClient
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real iOS Darwin/NSURLSession, test-owned localhost HTTPS only; no production trust override. */
class IosGenericSingleHopTest {
    @Test
    fun getAndPostReturnOneHopWithoutFollowingTheCredentialedLocation() = wireTest { fixture ->
        val get = fixture.execute("/redirect/get")
        assertEquals(302, get.status)
        val post = fixture.execute("/redirect/post", SourceHttpMethod.POST_JSON)
        assertEquals(307, post.status)
        val state = fixture.state()
        assertEquals(1, state["redirect_get"])
        assertEquals(1, state["redirect_post"])
        fixture.assertNoUnrequestedTraffic(state)
    }

    @Test
    fun exposedAmbiguityFailsClosedAndRawWireCollapseIsReported() = wireTest { fixture ->
        for (case in listOf("distinct", "identical", "case", "folded")) {
            inspectLocation(fixture, case)
        }
        val escaped = fixture.execute("/location/escaped")
        assertEquals(302, escaped.status)
        assertEquals("/forbidden/part%2Ctwo", escaped.location())
        assertEquals("ok", fixture.execute("/ok").body)
        fixture.assertNoUnrequestedTraffic(fixture.state())
    }

    @Test
    fun callerCancellationReleasesTheSocketAndOwnedCloseKeepsAnotherClientUsable() = wireTest { fixture ->
        cancelStalledResponse(fixture)
        assertEquals("ok", fixture.execute("/ok").body)
        assertTrue(fixture.client.coroutineContext.job.isActive)
        assertTrue(fixture.client.engine.coroutineContext.job.isActive)
        fixture.assertNoUnrequestedTraffic(fixture.state())
    }

    private fun wireTest(block: suspend (WireFixture) -> Unit) = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) {
            val fixture = WireFixture()
            try {
                assertEquals(fixture.id, fixture.execute("/ready").body)
                withTimeout(40_000) { block(fixture) }
            } finally {
                closeClient(fixture.client, "fixture-client-close")
            }
        }
    }

    private suspend fun inspectLocation(fixture: WireFixture, case: String) {
        val exposed = CompletableDeferred<List<String>>()
        val path = "/location/$case"
        val client = observingClient(path, exposed)
        try {
            var response: SourceResponse? = null
            var failure: Exception? = null
            try {
                response = KtorHttpExecutor(client).execute(fixture.request(path))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                failure = cause
            }
            val locations = if (exposed.isCompleted) exposed.await() else null
            val ambiguous = locations != null && (locations.size > 1 || locations.any { ',' in it })
            if (ambiguous) assertIs<SerializationException>(failure)
            if (failure == null) assertEquals(302, response?.status)
            println("KIRA_SINGLE_HOP_LOCATION case=$case exposed=${locations?.size} " +
                "ambiguous=$ambiguous status=${response?.status} failure=${failure?.let { it::class.simpleName }}")
            fixture.assertNoUnrequestedTraffic(fixture.state())
        } finally {
            closeClient(client, "location-client-close")
        }
    }

    private suspend fun cancelStalledResponse(fixture: WireFixture) = coroutineScope {
        val cancelled = CompletableDeferred<Unit>()
        val client = createHttpClient(cacheResponses = false, followRedirects = false)
        val request = launch {
            try {
                KtorHttpExecutor(client).execute(fixture.request("/stall"))
                error("Stalled fixture response completed without cancellation")
            } catch (cause: CancellationException) {
                cancelled.complete(Unit)
                throw cause
            }
        }
        try {
            fixture.awaitStarted() // Native engines need not expose headers for a one-byte incomplete body.
            assertFalse(request.isCompleted)
            request.cancel(CancellationException("single-hop fixture caller cancellation"))
            boundedPhase("request-cancel-join") { request.join(); cancelled.await() }
            fixture.awaitDisconnected() // Prove caller cancellation before owner close can release the socket.
        } finally {
            withContext(NonCancellable) {
                try {
                    boundedPhase("request-finally-join") { request.cancelAndJoin() }
                } finally {
                    closeClient(client, "cancel-client-close")
                }
            }
        }
    }

    private fun observingClient(path: String, exposed: CompletableDeferred<List<String>>): HttpClient {
        val client = createHttpClient(cacheResponses = false, followRedirects = false)
        client.receivePipeline.intercept(HttpReceivePipeline.Before) {
            if (subject.call.request.url.encodedPath == path) {
                exposed.complete(subject.headers.entries()
                    .filter { it.key.equals(HttpHeaders.Location, ignoreCase = true) }.flatMap { it.value })
            }
            proceed()
        }
        return client
    }

    private suspend fun closeClient(client: HttpClient, phase: String) = withContext(NonCancellable + Dispatchers.Default) {
        client.close() // Graceful: callers cancel active requests before asking the owner to close.
        try {
            boundedPhase(phase) {
                client.coroutineContext.job.join()
                client.engine.coroutineContext.job.join()
            }
            assertTrue(client.coroutineContext.job.isCompleted)
            assertTrue(client.engine.coroutineContext.job.isCompleted)
        } finally {
            // Last-resort cleanup cannot turn a failed bounded join into success.
            client.coroutineContext.cancel()
            client.engine.coroutineContext.cancel()
        }
    }

    private class WireFixture {
        val origin = setting("KIRA_SINGLE_HOP_ORIGIN").also {
            require(Regex("https://localhost:[1-9][0-9]{0,4}").matches(it))
            require(it.substringAfterLast(':').toInt() <= 65535)
        }
        val id = setting("KIRA_SINGLE_HOP_FIXTURE_ID").also { require(Regex("[0-9a-f]{32}").matches(it)) }
        val client = createHttpClient(cacheResponses = false, followRedirects = false)
        private val executor = KtorHttpExecutor(client)

        fun request(path: String, method: SourceHttpMethod = SourceHttpMethod.GET) = SourceRequest(
            url = origin + path,
            method = method,
            headers = mapOf("X-Kira-Fixture" to id, "X-Source-Sentinel" to "synthetic-only", "Cookie" to "test=only"),
            jsonBody = if (method == SourceHttpMethod.POST_JSON) "{}" else null,
        )

        suspend fun execute(path: String, method: SourceHttpMethod = SourceHttpMethod.GET) =
            executor.execute(request(path, method))

        suspend fun state(): Map<String, Int> {
            val response = execute("/state")
            assertEquals(200, response.status)
            return response.body.lineSequence().filter { it.isNotEmpty() }.associate {
                val cells = it.split('=', limit = 2)
                cells[0] to cells[1].toInt()
            }
        }

        fun assertNoUnrequestedTraffic(state: Map<String, Int>) {
            assertEquals(0, state["forbidden"])
            assertEquals(0, state["bad_requests"])
        }

        suspend fun awaitStarted() = boundedPhase("server-start") {
            while (state()["streams_started"] != 1) delay(25)
            val state = state()
            assertEquals(0, state["streams_closed"])
            assertEquals(0, state["streams_timed_out"])
        }

        suspend fun awaitDisconnected() = boundedPhase("socket-disconnect-before-close") {
            while (state()["streams_closed"] != 1) delay(25)
            val state = state()
            assertEquals(1, state["streams_started"])
            assertEquals(0, state["streams_timed_out"])
        }
    }

    private fun SourceResponse.location(): String? = headers.entries
        .singleOrNull { it.key.equals(HttpHeaders.Location, ignoreCase = true) }?.value

    private companion object {
        suspend fun <T> boundedPhase(phase: String, block: suspend () -> T): T {
            try {
                return withTimeout(5_000) { block() }
            } catch (cause: TimeoutCancellationException) {
                println("KIRA_SINGLE_HOP_TIMEOUT phase=$phase")
                throw cause
            }
        }

        fun setting(name: String): String = checkNotNull(NSProcessInfo.processInfo.environment[name] as? String) { "Missing owned single-hop fixture" }
    }
}
