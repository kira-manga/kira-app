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

class ComplaintEditBodyTest {
    @Test
    fun onlyDirectEdit200GetsThirtyTwoKiBAndEveryStatusOrErrorStopsAtSixteenPlusOne() =
        runTest {
            for (direct in listOf(false, true)) {
                for (error in listOf(false, true)) {
                    for (extra in 0..1) assertEditBoundary(direct, error, extra)
                }
            }
        }

    @Test
    fun selectedHeaderMultiplicityMediaCodingFramingAndUtf8FailClosedWithCleanup() =
        runTest {
            for (direct in listOf(false, true)) {
                val bytes = editBodyText(direct, HttpStatusCode.OK).encodeToByteArray()
                for (headers in invalidEditBodyHeaders(direct, bytes.size)) {
                    assertInvalidEditBody(bytes, headers, direct)
                }
                assertInvalidEditBody(byteArrayOf(0xc3.toByte(), 0x28), mobileEditHeaders(direct = direct), direct)
            }
        }

    @Test
    fun cleanMissingChunkedAndDeclaredEofKeepDirectAndStatusMatricesSeparate() =
        runTest {
            for (direct in listOf(false, true)) {
                val text = editBodyText(direct, HttpStatusCode.OK)
                for (framing in listOf("missing", "chunked", "declared")) {
                    val channel = HistoryTrackedChannel(ByteReadChannel(text))
                    val headers = editFramingHeaders(direct, HttpStatusCode.OK, framing, text.length)
                    val f = ComplaintEditHttpFixture(this) { respond(channel, HttpStatusCode.OK, headers) }
                    try {
                        f.assertEditBodyResult(direct, error = false)
                        assertTrue(channel.cancelled)
                        assertEquals(1, f.requests.size)
                    } finally {
                        f.close()
                    }
                }
            }
        }

    @Test
    fun failedEofBeforeOrAfterValidPrefixNeverPublishesAckStatusOrUnauthorizedFact() =
        runTest {
            for (direct in listOf(false, true)) {
                for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
                    for (framing in listOf("missing", "chunked", "declared")) {
                        for (afterPrefix in listOf(false, true)) {
                            assertEditFailedEof(direct, status, framing, afterPrefix)
                        }
                    }
                }
            }
        }
}

private suspend fun TestScope.assertEditBoundary(direct: Boolean, error: Boolean, extra: Int) {
    val status = if (error) HttpStatusCode.PreconditionFailed else HttpStatusCode.OK
    val maximum = if (direct && !error) 32 * 1_024 else 16 * 1_024
    val text = editBodyText(direct, status)
    val bytes = (text + " ".repeat(maximum + extra - text.length)).encodeToByteArray()
    val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
    val f = ComplaintEditHttpFixture(this) { respond(channel, status, mobileEditHeaders(status, direct)) }
    try {
        if (extra == 0) {
            f.assertEditBodyResult(direct, error)
        } else {
            f.assertEditBodyFailure(direct)
        }
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertInvalidEditBody(bytes: ByteArray, headers: Headers, direct: Boolean) {
    val channel = HistoryTrackedChannel(ByteReadChannel(bytes))
    val f = ComplaintEditHttpFixture(this) { respond(channel, HttpStatusCode.OK, headers) }
    try {
        f.assertEditBodyFailure(direct)
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertEditFailedEof(
    direct: Boolean,
    status: HttpStatusCode,
    framing: String,
    afterPrefix: Boolean,
) {
    val bytes = editBodyText(direct, status).encodeToByteArray()
    val channel = ComplaintFailedEofChannel(bytes, afterPrefix)
    val headers = editFramingHeaders(direct, status, framing, bytes.size)
    val f = ComplaintEditHttpFixture(this) { respond(channel, status, headers) }
    try {
        f.assertEditBodyFailure(direct, ComplaintMutationFailure.TRANSPORT)
        assertEquals(afterPrefix, channel.prefixDrained)
        assertTrue(channel.cancelled)
        assertEquals(1, f.requests.size)
    } finally {
        f.close()
    }
}

private suspend fun ComplaintEditHttpFixture.assertEditBodyResult(direct: Boolean, error: Boolean) {
    if (direct) {
        val result = http.edit(request, session)
        if (error) {
            assertIs<ComplaintEditHttpResult.HttpFailure>(result)
        } else {
            assertIs<ComplaintEditHttpResult.Applied>(result)
        }
    } else {
        val result = http.editStatus(status, session)
        if (error) {
            assertIs<ComplaintEditStatusHttpResult.HttpFailure>(result)
        } else {
            assertIs<ComplaintEditStatusHttpResult.Applied>(result)
        }
    }
}

private suspend fun ComplaintEditHttpFixture.assertEditBodyFailure(
    direct: Boolean,
    reason: ComplaintMutationFailure = ComplaintMutationFailure.RESPONSE,
) {
    if (direct) {
        assertEquals(reason, assertIs<ComplaintEditHttpResult.Failed>(http.edit(request, session)).reason)
    } else {
        assertEquals(reason, assertIs<ComplaintEditStatusHttpResult.Failed>(http.editStatus(status, session)).reason)
    }
}

private fun editBodyText(direct: Boolean, status: HttpStatusCode): String =
    when {
        status == HttpStatusCode.Unauthorized -> mutationProblem(status, "UNAUTHORIZED")
        status != HttpStatusCode.OK -> mutationProblem(status, "PRECONDITION_FAILED")
        direct -> mobileEditAck()
        else -> mobileEditApplied()
    }

private fun editFramingHeaders(direct: Boolean, status: HttpStatusCode, framing: String, bytes: Int): Headers =
    mobileEditHeaders(status, direct) {
        when (framing) {
            "chunked" -> append(HttpHeaders.TransferEncoding, "chunked")
            "declared" -> append(HttpHeaders.ContentLength, bytes.toString())
            else -> Unit
        }
    }

private fun invalidEditBodyHeaders(direct: Boolean, bytes: Int): List<Headers> =
    invalidEditHeaderChanges(bytes).map { mobileEditHeaders(direct = direct, change = it) } +
        if (direct) {
            listOf(
                mobileEditHeaders { remove(HttpHeaders.ETag) },
                mobileEditHeaders { append(HttpHeaders.ETag, "\"complaint-$MOBILE_EDIT_ID-v8\"") },
            )
        } else {
            listOf(mobileEditHeaders(direct = false) { append(HttpHeaders.ETag, "\"complaint-$MOBILE_EDIT_ID-v8\"") })
        }

private fun invalidEditHeaderChanges(bytes: Int): List<HeadersBuilder.() -> Unit> =
    listOf(
        { remove(ComplaintBoundedResponse.CONTRACT_HEADER) },
        { append(ComplaintBoundedResponse.CONTRACT_HEADER, "1") },
        { set(HttpHeaders.CacheControl, "no-store") },
        { append(HttpHeaders.CacheControl, "no-store, no-transform") },
        { append(HttpHeaders.ContentType, "application/json") },
        { set(HttpHeaders.ContentType, "application/json; charset=iso-8859-1") },
        { append(HttpHeaders.ContentEncoding, "gzip") },
        { append(HttpHeaders.ContentLength, "${bytes + 1}") },
        { append(HttpHeaders.ContentLength, "${bytes - 1}") },
        { append(HttpHeaders.ContentLength, "32769") },
        { append(HttpHeaders.ContentLength, "1,1") },
        {
            append(HttpHeaders.ContentLength, bytes.toString())
            append(HttpHeaders.ContentLength, bytes.toString())
        },
        {
            append(HttpHeaders.ContentLength, bytes.toString())
            append(HttpHeaders.TransferEncoding, "chunked")
        },
        { append(HttpHeaders.TransferEncoding, "gzip") },
        { append(HttpHeaders.Location, "/api/v1/complaints/$MOBILE_EDIT_ID") },
        { append(HttpHeaders.ETag, "x".repeat(129)) },
        { append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"kira-complaints\"") },
    )
