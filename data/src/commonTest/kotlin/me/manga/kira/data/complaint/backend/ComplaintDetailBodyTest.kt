package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintDetailBodyTest {
    @Test
    fun successCapIsThirtyTwoKiBAndNotFoundCapIsSixteenKiBBothWithLimitPlusOneCleanup() =
        runTest {
            for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
                val success = status == HttpStatusCode.OK
                val maximum = if (success) 32 * 1_024 else 16 * 1_024
                val text = if (success) historyItem().toString() else detailNotFoundProblem()
                val headers = if (success) detailHeaders() else sessionHeaders(status)
                for (over in listOf(0, 1)) {
                    val channel = HistoryTrackedChannel(ByteReadChannel(paddedDetail(maximum + over, text)))
                    val fixture =
                        ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, headers) })
                    try {
                        val result = fixture.repository.loadComplaintDetail(historyId(100))
                        when {
                            over == 1 ->
                                assertIs<AppError.Network.Serialization>(assertIs<AppResult.Failure>(result).error)
                            success -> assertIs<ComplaintDetail.Owned>(assertIs<AppResult.Success<*>>(result).value)
                            else -> assertSame(ComplaintDetail.Unavailable, assertIs<AppResult.Success<*>>(result).value)
                        }
                        assertTrue(channel.cancelled)
                        assertEquals(1, fixture.historyRequests.size)
                        fixture.assertPreserved()
                    } finally {
                        fixture.close()
                    }
                }
            }
        }

    @Test
    fun invalidHeadersFramingTagMultiplicityAndUtf8AreRejectedBeforePublication() =
        runTest {
            val body = historyItem().toString().encodeToByteArray()
            val cases =
                listOf(
                    detailHeaders { remove(ComplaintBoundedResponse.CONTRACT_HEADER) },
                    detailHeaders { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
                    detailHeaders { set(HttpHeaders.CacheControl, "no-store") },
                    detailHeaders { append(HttpHeaders.ContentType, "application/json") },
                    detailHeaders { append(HttpHeaders.ContentEncoding, "gzip") },
                    detailHeaders { append(HttpHeaders.ContentLength, "${body.size + 1}") },
                    detailHeaders { append(HttpHeaders.ContentLength, "32769") },
                    detailHeaders { append(HttpHeaders.ContentLength, "${body.size - 1}") },
                    detailHeaders { append(HttpHeaders.ContentLength, "1,1") },
                    detailHeaders {
                        append(HttpHeaders.ContentLength, body.size.toString())
                        append(HttpHeaders.TransferEncoding, "chunked")
                    },
                    detailHeaders { append(HttpHeaders.Location, "https://foreign.invalid/detail") },
                    detailHeaders { append(HttpHeaders.ETag, detailActionTag()) },
                    detailHeaders { set(HttpHeaders.ETag, "x".repeat(129)) },
                    detailHeaders { remove(HttpHeaders.ETag) },
                )
            for (headers in cases) invalidBody(body, HttpStatusCode.OK, headers)
            invalidBody(byteArrayOf(0xc3.toByte(), 0x28), HttpStatusCode.OK, detailHeaders())
            invalidBody(
                detailNotFoundProblem().encodeToByteArray(),
                HttpStatusCode.NotFound,
                sessionHeaders(HttpStatusCode.NotFound) { append(HttpHeaders.ETag, detailActionTag()) },
            )
        }

    @Test
    fun cleanDeclaredAndChunkedEofAcceptContentOrUnavailableWithoutChangingBudgets() =
        runTest {
            for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
                val text = if (status == HttpStatusCode.OK) historyItem().toString() else detailNotFoundProblem()
                for (framing in listOf("missing", "chunked", "declared")) {
                    val channel = HistoryTrackedChannel(ByteReadChannel(text))
                    val headers = detailFramingHeaders(status, framing, text.encodeToByteArray().size)
                    val fixture =
                        ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, headers) })
                    try {
                        assertIs<AppResult.Success<*>>(fixture.repository.loadComplaintDetail(historyId(100)))
                        assertTrue(channel.cancelled)
                        assertEquals(1, fixture.historyRequests.size)
                        assertEquals(1, fixture.sessionRequests.size)
                        fixture.assertPreserved()
                    } finally {
                        fixture.close()
                    }
                }
            }
        }

    @Test
    fun failedEofNeverPublishesContentOrUnavailableOrRefreshesSyntacticallyValid401() =
        runTest {
            for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)) {
                for (framing in listOf("missing", "chunked", "declared")) {
                    for (afterPrefix in listOf(false, true)) failedEof(status, framing, afterPrefix)
                }
            }
        }

    private suspend fun TestScope.invalidBody(
        bytes: ByteArray,
        status: HttpStatusCode,
        headers: Headers,
    ) {
        val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
        val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, headers) })
        try {
            val error = assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error
            assertIs<AppError.Network.Serialization>(error)
            assertNull(error.cause)
            assertTrue(channel.cancelled)
            assertEquals(1, fixture.historyRequests.size)
            assertEquals(1, fixture.sessionRequests.size)
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.failedEof(
        status: HttpStatusCode,
        framing: String,
        afterPrefix: Boolean,
    ) {
        val text =
            when (status) {
                HttpStatusCode.OK -> historyItem().toString()
                HttpStatusCode.NotFound -> detailNotFoundProblem()
                else -> historyProblem(status)
            }
        val channel = ComplaintFailedEofChannel(text.encodeToByteArray(), afterPrefix)
        val headers = detailFramingHeaders(status, framing, text.encodeToByteArray().size)
        val fixture = ComplaintHistoryFixture(this, historyHandler = { respond(channel, status, headers) })
        try {
            val error = assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error
            assertIs<AppError.Network.NoConnectivity>(error)
            assertNull(error.cause)
            assertEquals(afterPrefix, channel.prefixDrained)
            assertTrue(channel.cancelled)
            assertEquals(1, fixture.historyRequests.size)
            assertEquals(1, fixture.sessionRequests.size)
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }
}

private fun detailFramingHeaders(
    status: HttpStatusCode,
    framing: String,
    bytes: Int,
): Headers =
    sessionHeaders(status) {
        if (status == HttpStatusCode.OK) append(HttpHeaders.ETag, detailActionTag())
        when (framing) {
            "chunked" -> append(HttpHeaders.TransferEncoding, "chunked")
            "declared" -> append(HttpHeaders.ContentLength, bytes.toString())
            else -> Unit
        }
    }
