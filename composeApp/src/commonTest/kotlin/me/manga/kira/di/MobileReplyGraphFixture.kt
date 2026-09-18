package me.manga.kira.di

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Reuses the established graph's stores/owners, changing only the mutation response and writable test slots. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MobileReplyGraphFixture(
    private val scope: TestScope,
    private val fixture: ComplaintBackendGraphFixture,
) {
    val pending = MobileReplyGraphPending()
    val requests = mutableListOf<HttpRequestData>()
    private val sentBodies = mutableListOf<String>()

    fun resources(): ComplaintBackendResources {
        val original = fixture.resources()
        return ComplaintBackendResources(
            original.credentials,
            { pending },
            original.generator,
            ComplaintBackendEngineFactories(
                original.engines.enrollment,
                original.engines.session,
                original.engines.history,
                mutation = { target ->
                    assertEquals("$GRAPH_BASE${Policy.CREATE_PATH}", target.toString())
                    newMutationOwner()
                },
                deletion = original.engines.deletion,
            ),
            original.inputs,
        )
    }

    private fun newMutationOwner(): GraphEngineOwner {
        fixture.events += "allocate:mutation"
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        fixture.mutationCalls++
                        requests += request
                        sentBodies += request.body.toByteArray().decodeToString()
                        assertMayBeforeDispatch()
                        respond("""{"id":"$GRAPH_REPLY_ID","version":1}""", HttpStatusCode.Created, replyGraphHeaders())
                    }
                },
            )
        return GraphEngineOwner("mutation", engine, fixture.events, { false }, { false })
            .also { fixture.owners["mutation"] = it }
    }

    private fun assertMayBeforeDispatch() {
        val retained =
            assertIs<JsonObject>(
                Json.parseToJsonElement(
                    pending.slots
                        .single()
                        .bytes()
                        .decodeToString(),
                ),
            )
        assertEquals("MAY_HAVE_DISPATCHED", retained.getValue("state").jsonPrimitive.content)
        assertEquals("CREATE_REPLY", retained.getValue("operation").jsonPrimitive.content)
    }

    fun assertExactReply() {
        val request = requests.single()
        assertEquals(
            "$GRAPH_BASE${Policy.CREATE_PATH}/$GRAPH_REPLY_PARENT${Policy.REPLIES_SUFFIX}",
            request.url.toString(),
        )
        assertEquals(GRAPH_REPLY_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
        val body = assertIs<JsonObject>(Json.parseToJsonElement(sentBodies.single()))
        assertEquals(setOf("id", "body", "metadata"), body.keys)
        assertEquals(GRAPH_REPLY_ID, body.getValue("id").jsonPrimitive.content)
        assertEquals(GRAPH_REPLY_BODY, body.getValue("body").jsonPrimitive.content)
        val metadata = assertIs<JsonObject>(body["metadata"])
        assertEquals(setOf("appVersion", "osVersion", "manufacturer", "deviceModel"), metadata.keys)
        assertEquals("1.0.0", metadata.getValue("appVersion").jsonPrimitive.content)
        assertEquals(
            listOf("PREPARED", "MAY_HAVE_DISPATCHED"),
            pending.transitions.map { it.getValue("state").jsonPrimitive.content },
        )
        assertTrue(pending.transitions.all { it.getValue("parentId").jsonPrimitive.content == GRAPH_REPLY_PARENT })
        assertTrue(pending.transitions.all { it.getValue("targetId").jsonPrimitive.content == GRAPH_REPLY_ID })
        assertFalse(pending.transitions.any { it.toString().contains(GRAPH_REPLY_BODY) || "body" in it })
        assertTrue(pending.slots.isEmpty())
        assertEquals(1, pending.deletes)
    }
}

private fun replyGraphHeaders(): Headers =
    Headers.build {
        append("X-Kira-Complaint-Contract", "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
        append(HttpHeaders.ContentType, "application/json")
        append(HttpHeaders.Location, "${Policy.CREATE_PATH}/$GRAPH_REPLY_ID")
        append(HttpHeaders.ETag, "\"complaint-$GRAPH_REPLY_ID-v1\"")
    }

internal const val GRAPH_REPLY_PARENT = "55555555-5555-1555-8555-555555555555"
internal const val GRAPH_REPLY_ID = "33333333-3333-4333-8333-333333333333"
internal const val GRAPH_REPLY_KEY = "44444444-4444-4444-8444-444444444444"
internal const val GRAPH_REPLY_BODY = "coherent private reply body"
