package me.manga.kira.presentation.features.complaint.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplaintFirestoreRestDataSourceTest {
    private val config =
        ComplaintFirestoreRestConfig(
            projectId = "test-project",
            apiKey = "test-api-key",
        )

    private fun dataSource(
        requests: MutableList<HttpRequestData>,
        responder: suspend (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ): ComplaintFirestoreRestDataSource {
        val engine =
            MockEngine { request ->
                requests += request
                val (body, status) = responder(request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return ComplaintFirestoreRestDataSource(client, config)
    }

    private fun complaint(id: String = "doc123") =
        Complaint(
            id = id,
            userId = "device-user",
            type = ComplaintType.TECHNICAL,
            subject = "A subject",
            body = "A body",
            status = ComplaintStatus.OPEN,
            metadata = mapOf("appVersion" to "1.0.0"),
        )

    @Test
    fun create_usesInjectedFirebaseConfiguration_withoutAuthorizationHeader() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val source =
                dataSource(requests) {
                    """{"name":"projects/test-project/databases/(default)/documents/complaints_v2/doc123"}""" to
                        HttpStatusCode.OK
                }

            assertEquals("doc123", source.sendComplaint(complaint(id = "")))

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "/v1/projects/test-project/databases/(default)/documents/complaints_v2",
                request.url.encodedPath,
            )
            assertEquals("test-api-key", request.url.parameters["key"])
            assertNull(request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun readPaths_decodeDocuments_andPageTheAdminList() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            var listPage = 0
            val document =
                """
                {"name":"projects/test-project/databases/(default)/documents/complaints_v2/doc123","fields":{
                  "userId":{"stringValue":"device-user"},"type":{"stringValue":"TECHNICAL"},
                  "subject":{"stringValue":"Subject"},"body":{"stringValue":"Body"},
                  "status":{"stringValue":"OPEN"},"createdAt":{"timestampValue":"2026-07-18T10:00:00Z"}
                }}
                """.trimIndent()
            val source =
                dataSource(requests) { request ->
                    when {
                        request.url.toString().contains("runQuery") ->
                            """[{"document":$document}]""" to HttpStatusCode.OK
                        listPage++ == 0 -> """{"documents":[$document],"nextPageToken":"next"}""" to HttpStatusCode.OK
                        else -> """{"documents":[]}""" to HttpStatusCode.OK
                    }
                }

            val userDocs = source.getComplaintsByUser(" device-user ")
            val allDocs = source.getAllComplaints()

            assertEquals(listOf("doc123"), userDocs.map { it.id })
            assertEquals(listOf("doc123"), allDocs.map { it.id })
            assertEquals(3, requests.size)
            assertEquals("next", requests.last().url.parameters["pageToken"])
            assertTrue(requests.all { it.url.parameters["key"] == "test-api-key" })
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == null })
        }

    @Test
    fun updateAndDelete_targetOnlyValidatedDocumentIds() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val source = dataSource(requests) { "{}" to HttpStatusCode.OK }

            source.updateComplaint(complaint())
            source.deleteComplaint("doc123")

            assertEquals(listOf(HttpMethod.Patch, HttpMethod.Delete), requests.map { it.method })
            assertTrue(requests.all { it.url.encodedPath.endsWith("/complaints_v2/doc123") })
            assertFailsWith<IllegalArgumentException> { source.deleteComplaint("../other") }
            assertEquals(2, requests.size, "invalid IDs must be rejected before network I/O")
        }

    @Test
    fun failures_doNotExposeTheServerResponseBody() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val source = dataSource(requests) { "PRIVATE_SERVER_PAYLOAD" to HttpStatusCode.Forbidden }

            val failure =
                assertFailsWith<IllegalStateException> {
                    source.sendComplaint(complaint(id = ""))
                }

            assertFalse(failure.message.orEmpty().contains("PRIVATE_SERVER_PAYLOAD"))
            assertTrue(failure.message.orEmpty().contains("403"))
        }

    @Test
    fun missingRuntimeConfiguration_failsBeforeNetworkIo() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val source =
                ComplaintFirestoreRestDataSource(
                    httpClient = HttpClient(MockEngine { error("network must not be reached") }),
                    config = ComplaintFirestoreRestConfig(projectId = "", apiKey = ""),
                )

            assertFailsWith<IllegalStateException> { source.sendComplaint(complaint(id = "")) }
            assertTrue(requests.isEmpty())
        }
}
