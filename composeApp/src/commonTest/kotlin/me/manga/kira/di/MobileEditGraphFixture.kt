package me.manga.kira.di

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Borrows the existing graph/owner and exact-CAS test store; only synthetic edit bytes and key input differ. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MobileEditGraphFixture(
    private val scope: TestScope,
    private val fixture: ComplaintBackendGraphFixture,
) {
    val pending = MobileReplyGraphPending()
    val requests = mutableListOf<HttpRequestData>()
    val sentBodies = mutableListOf<String>()
    var editKeyGenerations = 0
    var rejectDirect = false

    fun resources(): ComplaintBackendResources {
        val original = fixture.resources()
        return ComplaintBackendResources(
            original.credentials,
            { pending },
            original.generator,
            editEngines(original),
            editInputs(original),
        )
    }

    private fun editEngines(original: ComplaintBackendResources): ComplaintBackendEngineFactories =
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

    private fun editInputs(original: ComplaintBackendResources): ComplaintBackendInputFactories =
        ComplaintBackendInputFactories(
            reports = {
                val reports = original.inputs.reports()
                ComplaintReportInputs(reports.identifiers, reports.metadata) {
                    editKeyGenerations++
                    GRAPH_EDIT_KEY
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
                        recordEditRequest(request)
                        if (rejectDirect) {
                            respond(
                                GRAPH_EDIT_NO_CHANGE,
                                HttpStatusCode.Conflict,
                                graphHeaders(HttpStatusCode.Conflict),
                            )
                        } else {
                            respond("""{"id":"$GRAPH_EDIT_ID","version":8}""", HttpStatusCode.OK, graphEditHeaders(8))
                        }
                    }
                },
            )
        return GraphEngineOwner("mutation", engine, fixture.events, { false }, { false })
            .also { fixture.owners["mutation"] = it }
    }

    private suspend fun recordEditRequest(request: HttpRequestData) {
        fixture.mutationCalls++
        requests += request
        sentBodies += request.body.toByteArray().decodeToString()
        val retained = Json.parseToJsonElement(pending.slots.single().bytes().decodeToString()).jsonObject
        assertEquals("MAY_HAVE_DISPATCHED", retained.getValue("state").jsonPrimitive.content)
        assertEquals("EDIT_CONTENT", retained.getValue("operation").jsonPrimitive.content)
    }

    fun assertExactEdit() {
        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("$GRAPH_BASE${Policy.CREATE_PATH}/$GRAPH_EDIT_ID${Policy.CONTENT_SUFFIX}", request.url.toString())
        assertEquals(GRAPH_EDIT_KEY, request.headers[Policy.IDEMPOTENCY_HEADER])
        assertEquals("\"complaint-$GRAPH_EDIT_ID-v7\"", request.headers[HttpHeaders.IfMatch])
        val body = assertIs<JsonObject>(Json.parseToJsonElement(sentBodies.single()))
        assertEquals(setOf("subject", "body"), body.keys)
        assertEquals("Replacement subject", body.getValue("subject").jsonPrimitive.content)
        assertEquals(GRAPH_EDIT_BODY, body.getValue("body").jsonPrimitive.content)
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

internal fun graphEditTarget(): ComplaintOwnerRow.Report {
    val time = Instant.parse("2026-09-17T00:00:00Z")
    val fields =
        ComplaintOwnerFields(
            id = GRAPH_EDIT_ID,
            body = "Original body",
            status = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
            createdAt = time,
            updatedAt = time,
            version = 7,
            actionTag = "\"complaint-$GRAPH_EDIT_ID-v7\"",
            appVersion = null,
            platform = ComplaintHistoryPlatform.ANDROID,
            osVersion = null,
            manufacturer = null,
            deviceModel = null,
            closureReason = null,
        )
    return ComplaintOwnerRow.Report(fields, ComplaintHistoryType.Known(ComplaintType.TECHNICAL), "Original subject")
}

internal fun graphEditDetail(): JsonObject {
    val row = Json.parseToJsonElement(graphHistoryResponse()).jsonObject.getValue("items").jsonArray.single().jsonObject
    return JsonObject(
        row +
            mapOf(
                "id" to JsonPrimitive(GRAPH_EDIT_ID),
                "version" to JsonPrimitive(7),
                "actionTag" to JsonPrimitive("\"complaint-$GRAPH_EDIT_ID-v7\""),
            ),
    )
}

internal fun graphEditHeaders(version: Long): Headers =
    Headers.build {
        appendAll(graphHeaders())
        append(HttpHeaders.ETag, "\"complaint-$GRAPH_EDIT_ID-v$version\"")
    }

internal const val GRAPH_EDIT_ID = "123e4567-e89b-42d3-a456-426614174000"
internal const val GRAPH_EDIT_KEY = "77777777-7777-4777-8777-777777777777"
internal const val GRAPH_EDIT_BODY = "Replacement body"
private const val GRAPH_EDIT_NO_CHANGE =
    """{"type":"about:blank","title":"Conflict","status":409,"errors":[{"code":"COMPLAINT_NO_CHANGE","message":"No change."}]}"""
