package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplaintHistoryHttpTest {
    @Test
    fun fixedGetAddsOnlyLimitThenOpaqueCursorAndBearerWithoutARequestBody() = runTest {
        var calls = 0
        val fixture = ComplaintHistoryFixture(this, historyHandler = {
            calls++
            val response = if (calls == 1) historyResponse(listOf(historyItem(2)), cursor = "v1.A._")
                else historyResponse(listOf(historyItem(1)))
            respond(response, HttpStatusCode.OK, sessionHeaders())
        })
        try {
            val history = assertIs<ComplaintHistory.Backend>(
                assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints()).value,
            )
            assertEquals(2, history.items.size)
            assertEquals(
                listOf("$SESSION_BASE_URL/api/v1/complaints?limit=50", "$SESSION_BASE_URL/api/v1/complaints?limit=50&cursor=v1.A._"),
                fixture.historyRequests.map { it.url.toString() },
            )
            fixture.historyRequests.forEach { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(listOf("Bearer $SESSION_TOKEN"), request.headers.getAll(HttpHeaders.Authorization))
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertEquals("no-store, no-transform", request.headers[HttpHeaders.CacheControl])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertTrue(request.body.toByteArray().isEmpty())
            }
            assertTrue(fixture.enrollment.requests.isEmpty())
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successCapIsTwoMiBButProblemsRemainSixteenKiBAndEveryBodyCloses() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
            val document = if (status == HttpStatusCode.OK) historyResponse() else historyProblem(status)
            val maximum = if (status == HttpStatusCode.OK) 2 * 1_024 * 1_024 else 16 * 1_024
            for (over in listOf(0, 1)) {
                val bytes = (document + " ".repeat(maximum + over - document.length)).encodeToByteArray()
                val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
                val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, sessionHeaders(status)) })
                try {
                    val result = fixture.repository.loadUserComplaints()
                    if (over != 0) {
                        assertIs<AppError.Network.Serialization>(assertIs<AppResult.Failure>(result).error)
                    } else if (status == HttpStatusCode.OK) {
                        assertIs<AppResult.Success<*>>(result)
                    } else {
                        assertEquals(404, assertIs<AppError.Network.Http>(assertIs<AppResult.Failure>(result).error).statusCode)
                    }
                    assertTrue(channel.cancelled)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            assertCleanHistoryEofRemainsAccepted(status)
            for (framing in listOf("missing", "chunked", "declared")) {
                for (afterPrefix in listOf(false, true)) {
                    assertFailedHistoryEofCannotPublishOrRefresh(status, framing, afterPrefix)
                }
            }
        }
    }

    @Test
    fun invalidHeadersFramingUtf8AndRedirectStatusAreSanitizedAndNeverRetried() = runTest {
        val body = historyResponse().encodeToByteArray()
        val headers = listOf(
            sessionHeaders { remove(ComplaintBoundedResponse.CONTRACT_HEADER) },
            sessionHeaders { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
            sessionHeaders { set(HttpHeaders.CacheControl, "no-store") },
            sessionHeaders { append(HttpHeaders.ContentType, "application/json") },
            sessionHeaders { append(HttpHeaders.ContentEncoding, "gzip") },
            sessionHeaders { append(HttpHeaders.ContentLength, "${body.size + 1}") },
            sessionHeaders { append(HttpHeaders.ContentLength, "2097153") },
            sessionHeaders { append(HttpHeaders.ContentLength, "${body.size}"); append(HttpHeaders.TransferEncoding, "chunked") },
            sessionHeaders { append(HttpHeaders.Location, "https://foreign.invalid/history") },
            sessionHeaders { append(HttpHeaders.ETag, "resource") },
        )
        for (header in headers) assertInvalidBody(body, header)
        assertInvalidBody(byteArrayOf(0xc3.toByte(), 0x28), sessionHeaders())
        for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.Found)) {
            val fixture = ComplaintHistoryFixture(this, historyHandler = { respond("", status, sessionHeaders(status)) })
            try {
                assertIs<AppError.Network.Serialization>(assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error)
                assertEquals(1, fixture.historyRequests.size)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun codeLess401NeedsExactChallengeAndMatchingStrictActualProblem() = runTest {
        val cases = listOf(
            historyProblem(HttpStatusCode.Unauthorized) to sessionHeaders(HttpStatusCode.Unauthorized) { remove(HttpHeaders.WWWAuthenticate) },
            historyProblem(HttpStatusCode.Unauthorized) to sessionHeaders(HttpStatusCode.Unauthorized) {
                set(HttpHeaders.WWWAuthenticate, "Bearer realm=\"other\"")
            },
            historyProblem(HttpStatusCode.NotFound) to sessionHeaders(HttpStatusCode.Unauthorized),
            """{"type":"about:blank","title":"Unauthorized","status":401,"code":"UNAUTHORIZED"}""" to
                sessionHeaders(HttpStatusCode.Unauthorized),
        )
        for ((body, headers) in cases) {
            val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(body, HttpStatusCode.Unauthorized, headers) })
            try {
                assertIs<AppError.Network.Serialization>(assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error)
                assertEquals(1, fixture.sessionRequests.size)
                assertEquals(1, fixture.historyRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun unavailableStatusesHaveNoRetryOrEnrollmentFallbackAndTransportTextNeverEscapes() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.Gone, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(historyProblem(status), status, sessionHeaders(status)) })
            try {
                val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
                assertEquals(status.value, assertIs<AppError.Network.Http>(error).statusCode)
                assertNull(error.cause)
                assertEquals(1, fixture.historyRequests.size)
                assertTrue(fixture.enrollment.requests.isEmpty())
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }
        val fixture = ComplaintHistoryFixture(this, historyHandler = { error("synthetic URL/header/body must not escape") })
        try {
            val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
            assertIs<AppError.Network.NoConnectivity>(error)
            assertNull(error.cause)
            assertEquals(1, fixture.historyRequests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ownedHistoryDeadlineIsTypedAndDoesNotRefreshOrClearEvidence() = runTest {
        val fixture = ComplaintHistoryFixture(this, historyHandler = { awaitCancellation() })
        try {
            val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
            assertIs<AppError.Network.Timeout>(error)
            assertNull(error.cause)
            assertEquals(1, fixture.historyRequests.size)
            assertEquals(1, fixture.sessionRequests.size)
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertInvalidBody(body: ByteArray, headers: Headers) {
        val channel = HistoryTrackedChannel(ByteReadChannel(body))
        val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(channel, HttpStatusCode.OK, headers) })
        try {
            val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
            assertIs<AppError.Network.Serialization>(error)
            assertNull(error.cause)
            assertEquals(1, fixture.historyRequests.size)
            assertTrue(channel.cancelled)
        } finally {
            fixture.close()
        }
    }
}

private suspend fun TestScope.assertFailedHistoryEofCannotPublishOrRefresh(
    status: HttpStatusCode,
    framing: String,
    afterPrefix: Boolean,
) {
    val text = if (status == HttpStatusCode.OK) historyResponse(listOf(historyItem())) else historyProblem(status)
    val bytes = text.encodeToByteArray()
    val channel = ComplaintFailedEofChannel(bytes, afterPrefix)
    val headers = sessionHeaders(status) {
        when (framing) {
            "chunked" -> append(HttpHeaders.TransferEncoding, "chunked")
            "declared" -> append(HttpHeaders.ContentLength, bytes.size.toString())
            else -> Unit
        }
    }
    val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, headers) })
    try {
        val error = assertIs<AppResult.Failure>(fixture.repository.loadUserComplaints()).error
        assertIs<AppError.Network.NoConnectivity>(error)
        assertNull(error.cause)
        assertEquals(afterPrefix, channel.prefixDrained)
        assertTrue(channel.cancelled)
        assertEquals(1, fixture.historyRequests.size)
        assertEquals(1, fixture.sessionRequests.size) // A syntactically valid failed401 never refreshes.
        assertTrue(fixture.enrollment.requests.isEmpty())
        fixture.assertPreserved()
    } finally {
        fixture.close()
    }
}

private suspend fun TestScope.assertCleanHistoryEofRemainsAccepted(status: HttpStatusCode) {
    val text = if (status == HttpStatusCode.OK) historyResponse(listOf(historyItem())) else historyProblem(status)
    val channel = HistoryTrackedChannel(ByteReadChannel(text))
    var calls = 0
    val fixture = ComplaintHistoryFixture(this, historyHandler = {
        if (++calls == 1) respond(channel, status, sessionHeaders(status))
        else respond(historyResponse(listOf(historyItem())), HttpStatusCode.OK, sessionHeaders())
    })
    try {
        val history = assertIs<ComplaintHistory.Backend>(assertIs<AppResult.Success<*>>(fixture.repository.loadUserComplaints()).value)
        assertEquals(1, history.items.size)
        assertEquals(if (status == HttpStatusCode.OK) 1 else 2, fixture.historyRequests.size)
        assertEquals(if (status == HttpStatusCode.OK) 1 else 2, fixture.sessionRequests.size)
        assertTrue(channel.cancelled)
        assertTrue(fixture.enrollment.requests.isEmpty())
        fixture.assertPreserved()
    } finally {
        fixture.close()
    }
}
