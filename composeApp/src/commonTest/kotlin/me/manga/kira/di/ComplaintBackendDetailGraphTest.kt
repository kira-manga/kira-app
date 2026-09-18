package me.manga.kira.di

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Existing isolated graph/engines/stores, with only synthetic read responses changed. Not shipping selection. */
class ComplaintBackendDetailGraphTest {
    @Test
    fun narrowListAndDetailPortsResolveSameOwnerAndTypedUseCaseReachesItsExactGet() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            val requests = mutableListOf<HttpRequestData>()
            val row = graphDetailRow()
            fixture.historyHandler = { request ->
                requests += request
                if (request.url.parameters.isEmpty()) {
                    respond(row.toString(), HttpStatusCode.OK, graphDetailHeaders(row))
                } else {
                    respond(graphHistoryResponse(), HttpStatusCode.OK, graphHeaders())
                }
            }
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()).detailGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            try {
                assertSame(graph.history, app.koin.get<ComplaintListRepository>())
                assertSame(graph.details, app.koin.get<ComplaintDetailRepository>())
                assertSame(app.koin.get<ComplaintListRepository>(), app.koin.get<ComplaintDetailRepository>())
                assertIs<ComplaintHistory.Backend>(app.koin.get<ObserveUserComplaintsUseCase>()().detailGraphSuccess())
                val id = row.getValue("id").jsonPrimitive.content
                val detail = app.koin.get<LoadComplaintDetailUseCase>()(id).detailGraphSuccess()
                assertEquals(id, assertIs<ComplaintOwnerRow.Report>(assertIs<ComplaintDetail.Owned>(detail).item).id)
                assertGraphDetailGet(requests.last(), id)
                assertEquals(2, fixture.historyCalls)
                assertEquals(1, fixture.sessionCalls)
                assertReadOnlyGraph(fixture)
            } finally {
                app.close()
                graph.close()
            }
            assertTrue(fixture.owners.values.all { it.closed })
        }

    @Test
    fun typedDetailDistinguishesExactUnavailableFromCodeLessDisabledFailure() =
        runTest {
            for (unavailable in listOf(false, true)) {
                val fixture = ComplaintBackendGraphFixture(this)
                fixture.historyHandler = {
                    val errors =
                        if (unavailable) ",\"errors\":[{\"code\":\"NOT_FOUND\",\"message\":\"Not found.\"}]" else ""
                    respond(
                        """{"type":"about:blank","title":"Not Found","status":404$errors}""",
                        HttpStatusCode.NotFound,
                        graphHeaders(HttpStatusCode.NotFound),
                    )
                }
                val graph = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()).detailGraphSuccess()
                val app = koinApplication { modules(graph.module()) }
                try {
                    val result = app.koin.get<LoadComplaintDetailUseCase>()(GRAPH_DETAIL_ID)
                    if (unavailable) {
                        assertSame(ComplaintDetail.Unavailable, result.detailGraphSuccess())
                    } else {
                        assertEquals(
                            404,
                            assertIs<AppError.Network.Http>(assertIs<AppResult.Failure>(result).error).statusCode,
                        )
                    }
                    assertEquals(1, fixture.historyCalls)
                    assertReadOnlyGraph(fixture)
                } finally {
                    app.close()
                    graph.close()
                }
            }
        }

    @Test
    fun missingIdentityCannotBootstrapEnrollOrGenerateThroughTheTypedDetailGraph() =
        runTest {
            val fixture = ComplaintBackendGraphFixture(this)
            fixture.credentials.missing = true
            val graph = createComplaintBackendGraph({ GRAPH_BASE }, fixture.resources()).detailGraphSuccess()
            val app = koinApplication { modules(graph.module()) }
            try {
                assertIs<AppResult.Failure>(app.koin.get<LoadComplaintDetailUseCase>()(GRAPH_DETAIL_ID))
                assertEquals(0, fixture.sessionCalls + fixture.historyCalls)
                assertTrue(fixture.credentials.missing)
                assertReadOnlyGraph(fixture)
            } finally {
                app.close()
                graph.close()
            }
        }

    @Test
    fun addingDetailBindingsCannotOpenShippingSelectionOrAllocateAnyResource() {
        var allocations = 0
        val result =
            selectComplaintBackendCandidate {
                createComplaintBackendGraph(
                    {
                        allocations++
                        GRAPH_BASE
                    },
                    trapGraphResources { allocations++ },
                )
            }
        assertIs<AppResult.Failure>(result)
        assertEquals(0, allocations)
    }
}

private suspend fun assertGraphDetailGet(
    request: HttpRequestData,
    id: String,
) {
    assertEquals(HttpMethod.Get, request.method)
    assertEquals("$GRAPH_BASE/api/v1/complaints/$id", request.url.toString())
    assertTrue(request.body.toByteArray().isEmpty())
    assertNull(request.headers["X-Kira-Idempotency-Key"])
    assertNull(request.headers[HttpHeaders.IfNoneMatch])
    assertNull(request.headers[HttpHeaders.Cookie])
}

private fun assertReadOnlyGraph(fixture: ComplaintBackendGraphFixture) {
    assertEquals(0, fixture.credentials.writes + fixture.pending.writes)
    assertEquals(0, fixture.generations + fixture.reportIdentifierGenerations + fixture.reportMetadataReads)
    assertEquals(0, fixture.mutationCalls + fixture.deletionCalls + fixture.deletionKeyGenerations)
    assertTrue(fixture.events.none { it == "request:enrollment" })
}

private fun graphDetailRow(): JsonObject =
    Json
        .parseToJsonElement(graphHistoryResponse())
        .jsonObject
        .getValue("items")
        .jsonArray
        .single()
        .jsonObject

private fun graphDetailHeaders(row: JsonObject): Headers =
    Headers.build {
        appendAll(graphHeaders())
        append(HttpHeaders.ETag, row.getValue("actionTag").jsonPrimitive.content)
    }

private fun <T> AppResult<T>.detailGraphSuccess(): T = assertIs<AppResult.Success<T>>(this).value

private const val GRAPH_DETAIL_ID = "22222222-2222-4222-a222-222222222222"
