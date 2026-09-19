package me.manga.kira.navigation.routes

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.di.ComplaintBackendGraphFixture
import me.manga.kira.di.ComplaintBackendResources
import me.manga.kira.di.GRAPH_BASE
import me.manga.kira.di.GRAPH_EDIT_ID
import me.manga.kira.di.GRAPH_REPLY_BODY
import me.manga.kira.di.GRAPH_REPLY_PARENT
import me.manga.kira.di.MobileReplyGraphFixture
import me.manga.kira.di.createComplaintBackendGraph
import me.manga.kira.di.graphEditDetail
import me.manga.kira.di.graphHeaders
import me.manga.kira.di.graphHistoryResponse
import me.manga.kira.domain.model.complaint.BackendNoticeKey
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.presentation.complaint.ComplaintDetailIntent
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEffect
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyIntent
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyViewModel
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real candidate decoder/VM chain with the existing reply HTTP/CAS fixture and child-drain helper. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Small explicit steps share one candidate and its owned lifetimes.
internal class ComplaintBackendNoticeReplyFixture(private val scope: TestScope, initialRow: JsonObject) {
    val backend = ComplaintBackendGraphFixture(scope)
    val reply = MobileReplyGraphFixture(scope, backend)
    val credentials = ActionHostCredentialReads(backend.credentials)
    var row = initialRow
    var historyRow: JsonObject? = initialRow
    var detailStatus = HttpStatusCode.OK
    var detailGate: CompletableDeferred<Unit>? = null
    var historyGate: CompletableDeferred<Unit>? = null
    val detailRequests = mutableListOf<HttpRequestData>()
    val graph: ComplaintBackendGraph
    val app: KoinApplication
    val detail: ComplaintBackendDetailOpening
    val owner: ComplaintBackendActionHostOwner
    private val deliveries = mutableListOf<Job>()

    init {
        backend.historyHandler = { request ->
            assertEquals(HttpMethod.Get, request.method)
            if (request.url.parameters.isEmpty()) {
                detailRequests += request
                detailGate?.await()
                if (detailStatus == HttpStatusCode.OK) {
                    respond(row.toString(), detailStatus, noticeDetailHeaders(row))
                } else {
                    respond(noticeReadProblem(detailStatus), detailStatus, graphHeaders(detailStatus))
                }
            } else {
                historyGate?.await()
                respond(noticeHistoryResponse(historyRow), HttpStatusCode.OK, graphHeaders())
            }
        }
        graph =
            assertIs<AppResult.Success<ComplaintBackendGraph>>(
                createComplaintBackendGraph({ GRAPH_BASE }, resources()),
            ).value
        app = koinApplication { modules(graph.module()) }
        detail = ComplaintBackendDetailOpening(app.koin)
        owner = ComplaintBackendActionHostOwner(app.koin, detail.detail)
    }

    private fun resources(): ComplaintBackendResources {
        val original = reply.resources()
        return ComplaintBackendResources(
            { credentials }, original.pending, original.generator, original.engines, original.inputs,
        )
    }

    fun idle() = scope.runCurrent()

    fun select(): ComplaintDetail {
        val history = assertIs<ComplaintHistory.Backend>(detail.history.state.value.history)
        val id = history.notices.singleOrNull()?.id ?: history.items.single().id
        detail.select(id)
        idle()
        return assertNotNull(detail.detail.state.value.detail)
    }

    fun refreshDetail() {
        detail.detail.submit(ComplaintDetailIntent.Retry)
        idle()
    }

    fun refreshHistory() {
        detail.history.submit(ComplaintIntent.OnRetry)
        idle()
    }

    fun open(target: ComplaintDetail = select()): ComplaintBackendActionOpening {
        owner.open(ComplaintBackendAction.REPLY, target)
        idle()
        return assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening
    }

    fun changeBody(opening: ComplaintBackendActionOpening) {
        opening.replyModel.submit(ComplaintReplyIntent.ChangeBody(GRAPH_REPLY_BODY))
        idle()
    }

    fun holdReply(opening: ComplaintBackendActionOpening): ActionHostReadHold {
        changeBody(opening)
        val hold = credentials.holdNext()
        opening.replyModel.submit(ComplaintReplyIntent.Submit)
        idle()
        assertTrue(hold.entered.isCompleted)
        return hold
    }

    fun collectFinish(opening: ComplaintBackendActionOpening): Job =
        scope.backgroundScope.launch {
            owner.actionFinished(opening, opening.requestsRecovery())
        }.also { deliveries += it }

    fun collectFinish(opening: ComplaintBackendRequestOpening): Job =
        scope.backgroundScope.launch {
            opening.viewModel.effects.first { it == SettingsFeedbackEffect.Closed }
            owner.recoveryFinished(opening)
        }.also { deliveries += it }

    fun requestClose(opening: ComplaintBackendActionOpening, recovery: Boolean) {
        opening.replyModel.submit(if (recovery) ComplaintReplyIntent.OpenRecovery else ComplaintReplyIntent.Close)
        idle()
    }

    fun assertNoWriteOrSetup() {
        assertTrue(reply.pending.slots.isEmpty() && reply.pending.transitions.isEmpty())
        assertTrue(reply.requests.isEmpty())
        assertEquals(0, backend.mutationCalls + backend.deletionCalls + backend.pending.writes)
        assertEquals(0, backend.credentials.writes + backend.generations + backend.deletionKeyGenerations)
        assertEquals(0, backend.reportIdentifierGenerations + backend.reportMetadataReads)
        assertTrue(backend.events.none { it == "request:enrollment" })
    }

    fun assertExactReply() {
        reply.assertExactReply()
        assertEquals(HttpMethod.Post, reply.requests.single().method)
        assertNull(reply.requests.single().headers[HttpHeaders.IfMatch])
        assertTrue(reply.pending.transitions.all { it["expectedVersion"] == JsonNull })
        assertEquals(1, backend.reportIdentifierGenerations)
        assertEquals(1, backend.reportMetadataReads)
        assertEquals(
            0,
            backend.credentials.writes + backend.generations + backend.deletionCalls + backend.pending.writes,
        )
        assertTrue(backend.events.none { it == "request:enrollment" })
    }

    fun close() {
        historyGate?.complete(Unit)
        detailGate?.complete(Unit)
        credentials.releaseAll()
        deliveries.forEach { it.cancel() }
        owner.close()
        detail.close()
        idle()
        app.close()
        graph.close()
        idle()
    }
}

internal val ComplaintBackendActionOpening.replyModel: ComplaintReplyViewModel
    get() = assertIs<ComplaintBackendActionPanel.Reply>(panel).viewModel

internal fun systemNoticeRow(key: String = BackendNoticeKey.CONTENT_POLICY.key): JsonObject =
    buildJsonObject {
        put("id", GRAPH_REPLY_PARENT)
        put("kind", "NOTICE")
        put("noticeKey", key)
        put("status", "PINNED")
        put("createdAt", "2026-09-17T00:00:00Z")
        put("updatedAt", "2026-09-17T00:00:00Z")
        put("version", 1)
    }

internal fun ownedNoticeReplyRow(): JsonObject =
    JsonObject(
        graphEditDetail() + mapOf(
            "id" to JsonPrimitive(GRAPH_REPLY_PARENT),
            "kind" to JsonPrimitive("REPLY"),
            "type" to JsonPrimitive("CUSTOM"),
            "subject" to JsonNull,
            "noticeKey" to JsonPrimitive(BackendNoticeKey.CONTENT_POLICY.key),
            "replyToId" to JsonPrimitive(GRAPH_EDIT_ID),
            "actionTag" to JsonPrimitive("\"complaint-$GRAPH_REPLY_PARENT-v7\""),
        ),
    )

private fun noticeDetailHeaders(row: JsonObject): Headers =
    Headers.build {
        appendAll(graphHeaders())
        if (row.getValue("kind").jsonPrimitive.content != "NOTICE") {
            append(HttpHeaders.ETag, row.getValue("actionTag").jsonPrimitive.content)
        }
    }

private fun noticeHistoryResponse(row: JsonObject?): String {
    val root = Json.parseToJsonElement(graphHistoryResponse(empty = true)).jsonObject
    if (row == null) return root.toString()
    val field = if (row.getValue("kind").jsonPrimitive.content == "NOTICE") "notices" else "items"
    return JsonObject(root + (field to JsonArray(listOf(row)))).toString()
}

private fun noticeReadProblem(status: HttpStatusCode): String =
    if (status == HttpStatusCode.NotFound) {
        """
        {"type":"about:blank","title":"Not Found","status":404,
         "errors":[{"code":"NOT_FOUND","message":"Unavailable"}]}
        """.trimIndent()
    } else {
        """{"type":"about:blank","title":"Service Unavailable","status":${status.value}}"""
    }

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withNoticeReplyHost(
    row: JsonObject = systemNoticeRow(),
    test: suspend ComplaintBackendNoticeReplyFixture.() -> Unit,
) {
    val fixture = ComplaintBackendNoticeReplyFixture(this, row)
    try {
        runCurrent()
        fixture.test()
    } finally {
        fixture.close()
    }
}
