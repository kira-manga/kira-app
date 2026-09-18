package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class ComplaintOwnerDeleteBodyTest {
    @Test
    fun zeroByteAcknowledgementRejectsTheFirstByteIncludingWhitespaceNullAndJsonWithCleanup() =
        runTest {
            assertEquals(0, Policy.MAX_OWNER_DELETE_ACKNOWLEDGEMENT_BYTES)
            for (text in listOf("", " ", "\n", "\u0000", "{}", "null", "x".repeat(16_384))) {
                assertOwnerDeleteBody(text)
            }
        }

    @Test
    fun any204LengthMediaTransferEtagLocationOrInvalidSecurityHeaderRejectsBeforeReading() =
        runTest {
            for (headers in invalidOwnerDeleteHeaders()) assertOwnerDeleteHeadersRejected(headers)
        }

    @Test
    fun absentOrIdentityCodingNeedsCleanEofAndFailureNeverBecomesAnEmptyAcknowledgement() =
        runTest {
            for (identity in listOf(false, true)) {
                for (failed in listOf(false, true)) assertOwnerDeleteEof(identity, failed)
            }
        }

    @Test
    fun deleteErrorsAndStatusStillUseTheSixteenKiBPlusOneJsonBoundary() =
        runTest {
            for (status in listOf(false, true)) {
                for (extra in 0..1) assertOwnerDeleteJsonBoundary(status, extra)
            }
        }

    @Test
    fun success200201202AndPartialContentCannotBecomeDelete204OrStatus200() =
        runTest {
            val wrongSuccesses =
                listOf(HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.Accepted, HttpStatusCode.PartialContent)
            for (code in wrongSuccesses) assertWrongOwnerDeleteSuccess(code)
            val f =
                ComplaintReportFixture(
                    this,
                    mutationHandler = { respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders()) },
                )
            try {
                val result = f.http.ownerDeleteStatus(mobileOwnerDeleteStatusRequest(), mutationSession())
                assertIs<ComplaintOwnerDeleteStatusHttpResult.Failed>(result)
            } finally {
                f.close()
            }
        }
}

private suspend fun TestScope.assertOwnerDeleteBody(text: String) {
    val channel = HistoryTrackedChannel(ByteReadChannel(text))
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = { respond(channel, HttpStatusCode.NoContent, mobileOwnerDeleteHeaders()) },
        )
    try {
        val result = f.http.ownerDelete(mobileOwnerDeleteHttpRequest(), mutationSession())
        if (text.isEmpty()) {
            assertIs<ComplaintOwnerDeleteHttpResult.Applied>(result)
        } else {
            assertOwnerDeleteRejectedBody(result)
        }
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertOwnerDeleteHeadersRejected(headers: Headers) {
    val channel = ComplaintFailedEofChannel(byteArrayOf(), afterPrefix = false)
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = { respond(channel, HttpStatusCode.NoContent, headers) },
        )
    try {
        val result = f.http.ownerDelete(mobileOwnerDeleteHttpRequest(), mutationSession())
        assertOwnerDeleteRejectedBody(result)
        assertTrue(channel.cancelled)
    } finally {
        f.close()
    }
}

private fun assertOwnerDeleteRejectedBody(result: ComplaintOwnerDeleteHttpResult) {
    val failed = assertIs<ComplaintOwnerDeleteHttpResult.Failed>(result)
    assertEquals(ComplaintMutationFailure.RESPONSE, failed.reason)
}

private suspend fun TestScope.assertWrongOwnerDeleteSuccess(code: HttpStatusCode) {
    val f = ComplaintReportFixture(this, mutationHandler = { respond("", code, mobileOwnerDeleteHeaders()) })
    try {
        val result = f.http.ownerDelete(mobileOwnerDeleteHttpRequest(), mutationSession())
        assertOwnerDeleteRejectedBody(result)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertOwnerDeleteEof(identity: Boolean, failed: Boolean) {
    val channel: ByteReadChannel =
        if (failed) {
            ComplaintFailedEofChannel(byteArrayOf(), afterPrefix = true)
        } else {
            HistoryTrackedChannel(ByteReadChannel(""))
        }
    val headers = mobileOwnerDeleteHeaders { if (identity) append(HttpHeaders.ContentEncoding, "identity") }
    val f = ComplaintReportFixture(this, mutationHandler = { respond(channel, HttpStatusCode.NoContent, headers) })
    try {
        val result = f.http.ownerDelete(mobileOwnerDeleteHttpRequest(), mutationSession())
        if (failed) {
            val failure = assertIs<ComplaintOwnerDeleteHttpResult.Failed>(result)
            assertEquals(ComplaintMutationFailure.TRANSPORT, failure.reason)
            assertTrue(assertIs<ComplaintFailedEofChannel>(channel).cancelled)
        } else {
            assertIs<ComplaintOwnerDeleteHttpResult.Applied>(result)
            assertTrue(assertIs<HistoryTrackedChannel>(channel).cancelled)
        }
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertOwnerDeleteJsonBoundary(status: Boolean, extra: Int) {
    val code = if (status) HttpStatusCode.OK else HttpStatusCode.PreconditionFailed
    val text = if (status) MOBILE_OWNER_DELETE_APPLIED else mutationProblem(code, "PRECONDITION_FAILED")
    val padded = text + " ".repeat(Policy.MAX_STATUS_OR_PROBLEM_BYTES + extra - text.length)
    val channel = HistoryTrackedChannel(ByteReadChannel(padded))
    val f = ComplaintReportFixture(this, mutationHandler = { respond(channel, code, mutationHeaders(code)) })
    try {
        if (status) {
            val result = f.http.ownerDeleteStatus(mobileOwnerDeleteStatusRequest(), mutationSession())
            if (extra == 0) {
                assertIs<ComplaintOwnerDeleteStatusHttpResult.Applied>(result)
            } else {
                assertIs<ComplaintOwnerDeleteStatusHttpResult.Failed>(result)
            }
        } else {
            val result = f.http.ownerDelete(mobileOwnerDeleteHttpRequest(), mutationSession())
            if (extra == 0) {
                assertIs<ComplaintOwnerDeleteHttpResult.HttpFailure>(result)
            } else {
                assertIs<ComplaintOwnerDeleteHttpResult.Failed>(result)
            }
        }
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private fun invalidOwnerDeleteHeaders(): List<Headers> =
    FORBIDDEN_DELETE_ACK_HEADERS.map { (name, value) -> mobileOwnerDeleteHeaders { append(name, value) } } +
        invalidDeleteSecurityHeaders().map { mobileOwnerDeleteHeaders(it) }

private fun invalidDeleteSecurityHeaders(): List<HeadersBuilder.() -> Unit> =
    listOf(
        { remove(ComplaintBoundedResponse.CONTRACT_HEADER) },
        { set(ComplaintBoundedResponse.CONTRACT_HEADER, "2") },
        { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
        { set(HttpHeaders.CacheControl, "no-store") },
        { append(HttpHeaders.CacheControl, "no-store, no-transform") },
        {
            append(HttpHeaders.ContentLength, "0")
            append(HttpHeaders.ContentLength, "0")
        },
        {
            append(HttpHeaders.ContentEncoding, "identity")
            append(HttpHeaders.ContentEncoding, "identity")
        },
    )

private val FORBIDDEN_DELETE_ACK_HEADERS =
    listOf(
        HttpHeaders.ContentLength to "0",
        HttpHeaders.ContentLength to "00",
        HttpHeaders.ContentLength to "1",
        HttpHeaders.ContentLength to "",
        HttpHeaders.ContentLength to "0,0",
        HttpHeaders.ContentType to "application/json",
        HttpHeaders.ContentType to "application/problem+json",
        HttpHeaders.ContentType to "",
        HttpHeaders.TransferEncoding to "chunked",
        HttpHeaders.TransferEncoding to "gzip",
        HttpHeaders.ETag to "\"complaint-$MOBILE_EDIT_ID-v8\"",
        HttpHeaders.ETag to "",
        HttpHeaders.Location to "/api/v1/complaints/$MOBILE_EDIT_ID",
        HttpHeaders.Location to "",
        HttpHeaders.ContentEncoding to "gzip",
        HttpHeaders.ContentEncoding to "identity,identity",
        HttpHeaders.WWWAuthenticate to "Bearer realm=\"kira-complaints\"",
        HttpHeaders.RetryAfter to "x".repeat(129),
    )
