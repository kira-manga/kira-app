package me.manga.kira.di

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.utils.EmptyContent
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Existing graph and exact-CAS store; only the synthetic key and bodyless mutation response differ. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MobileOwnerDeleteGraphFixture(
    private val scope: TestScope,
    private val fixture: ComplaintBackendGraphFixture,
) {
    val pending = MobileReplyGraphPending()
    val requests = mutableListOf<HttpRequestData>()
    var keyGenerations = 0

    fun resources(): ComplaintBackendResources {
        val original = fixture.resources()
        return ComplaintBackendResources(
            original.credentials,
            { pending },
            original.generator,
            ownerDeleteEngines(original),
            ownerDeleteInputs(original),
        )
    }

    private fun ownerDeleteEngines(original: ComplaintBackendResources): ComplaintBackendEngineFactories =
        ComplaintBackendEngineFactories(
            original.engines.enrollment,
            original.engines.session,
            original.engines.history,
            mutation = { target ->
                assertEquals("$GRAPH_BASE${Policy.CREATE_PATH}", target.toString())
                newMutationOwner()
            },
            deletion = original.engines.deletion,
        )

    private fun ownerDeleteInputs(original: ComplaintBackendResources): ComplaintBackendInputFactories =
        ComplaintBackendInputFactories(
            reports = {
                val reports = original.inputs.reports()
                ComplaintReportInputs(reports.identifiers, reports.metadata) {
                    keyGenerations++
                    GRAPH_OWNER_DELETE_KEY
                }
            },
            deletionKey = original.inputs.deletionKey,
        )

    private fun newMutationOwner(): GraphEngineOwner {
        fixture.events += "allocate:mutation"
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { request ->
                        recordOwnerDelete(request)
                        respond("", HttpStatusCode.NoContent, graphOwnerDeleteHeaders())
                    }
                },
            )
        return GraphEngineOwner("mutation", engine, fixture.events, { false }, { false })
            .also { fixture.owners["mutation"] = it }
    }

    private suspend fun recordOwnerDelete(request: HttpRequestData) {
        fixture.events += "request:mutation"
        fixture.mutationCalls++
        requests += request
        assertTrue(request.body.toByteArray().isEmpty())
        val retained = Json.parseToJsonElement(pending.slots.single().bytes().decodeToString()).jsonObject
        assertEquals("MAY_HAVE_DISPATCHED", retained.getValue("state").jsonPrimitive.content)
        assertEquals("DELETE_OWNED", retained.getValue("operation").jsonPrimitive.content)
    }

    fun assertExactOwnerDelete() {
        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$GRAPH_BASE${Policy.CREATE_PATH}/$GRAPH_EDIT_ID", request.url.toString())
        assertSame(EmptyContent, request.body)
        assertEquals(GRAPH_OWNER_DELETE_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
        assertEquals("\"complaint-$GRAPH_EDIT_ID-v7\"", request.headers[HttpHeaders.IfMatch])
        assertNull(request.headers[HttpHeaders.ContentType])
        assertNull(request.headers[HttpHeaders.ContentEncoding])
        assertNull(request.headers[HttpHeaders.TransferEncoding])
        assertTrue(request.headers.getAll(HttpHeaders.ContentLength) in listOf(null, listOf("0")))
        assertEquals(
            listOf("PREPARED", "MAY_HAVE_DISPATCHED"),
            pending.transitions.map { it.getValue("state").jsonPrimitive.content },
        )
        assertTrue(
            pending.transitions.all {
                it.keys.size == 19 && it.getValue("expectedVersion").jsonPrimitive.content == "7"
            },
        )
        assertTrue(pending.transitions.all { it.getValue("targetId").jsonPrimitive.content == GRAPH_EDIT_ID })
        assertTrue(pending.transitions.none { "body" in it || "subject" in it })
    }
}

private fun graphOwnerDeleteHeaders(): Headers =
    Headers.build {
        append("X-Kira-Complaint-Contract", "1")
        append(HttpHeaders.CacheControl, "no-store, no-transform")
    }

private const val GRAPH_OWNER_DELETE_KEY = "88888888-8888-4888-8888-888888888888"
