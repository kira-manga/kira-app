package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Synthetic delivered sources exercise the real guard, not illegal origin bytes hidden by protocol204. */
@Suppress("MagicNumber") // Exact native204 framing and byte/cancellation counts.
class AndroidComplaintMutationOwnerDeleteReceiveTest {
    @Test
    fun actualInterceptorBindsTheResponseToTheSameDeleteMethodTargetKeyTagAndSnapshot() {
        withNativeResponse { call, upstream ->
            call.execute().use { assertEquals("", it.body.string()) }
            assertFalse(call.isCanceled())
            assertEquals(1, upstream.closes)
        }
        responseSubstitutions().forEach { change ->
            withNativeResponse(change = change) { call, upstream ->
                assertFailsWith<IOException> { call.execute().close() }
                assertTrue(call.isCanceled())
                assertEquals(0, upstream.reads)
                assertEquals(1, upstream.closes)
            }
        }
    }

    @Test
    fun direct204LengthIncludingZeroMediaTransferAndBadCodingRejectBeforeReading() {
        listOf(
            listOf("Content-Length" to "0"),
            listOf("Content-Length" to "0", "content-length" to "0"),
            listOf("Content-Type" to "application/json"),
            listOf("Transfer-Encoding" to "chunked"),
            listOf("Content-Encoding" to "gzip"),
            listOf("Content-Encoding" to "identity", "Content-Encoding" to "identity"),
        ).forEach { headers ->
            withNativeResponse(headers = headers) { call, upstream ->
                assertFailsWith<IOException> { call.execute().close() }
                assertTrue(call.isCanceled())
                assertEquals(0, upstream.reads)
                assertEquals(1, upstream.closes)
            }
        }
    }

    @Test
    fun zeroBudgetStillReadsCleanEofAndThenClosesWithoutCancelling() {
        val upstream = DeleteProbeBody()
        val budget = deleteBudget()
        var cancellations = 0
        val body = AndroidComplaintSessionResponseBody(upstream, budget) { cancellations++ }
        val sink = Buffer()
        try {
            assertEquals(-1L, body.source().read(sink, 1))
            assertEquals(-1L, body.source().read(sink, 1))
            assertEquals(1, upstream.reads)
            assertEquals(0L, sink.size)
            assertEquals(0, budget.receivedBytes)
            body.close()
            body.close()
            assertEquals(0, cancellations)
            assertEquals(1, upstream.closes)
        } finally {
            body.close()
            sink.clear()
        }
    }

    @Test
    fun firstDeliveredByteIsNeverForwardedAndFailureCancelsOnlyOnce() {
        val upstream = DeleteProbeBody(actual = 1)
        val budget = deleteBudget()
        var cancellations = 0
        val body = AndroidComplaintSessionResponseBody(upstream, budget) { cancellations++ }
        val sink = Buffer()
        try {
            repeat(2) { assertFailsWith<IOException> { body.source().read(sink, 1) } }
            assertEquals(0L, sink.size)
            assertEquals(0, budget.receivedBytes)
            assertEquals(1, upstream.reads)
            assertEquals(1, cancellations)
            body.close()
            body.close()
            assertEquals(1, upstream.closes)
            assertEquals(1, cancellations)
        } finally {
            body.close()
            sink.clear()
        }
    }

    @Test
    fun failedEofCannotBecomeEmptySuccessAndOwnerCleanupClosesAndCancels() {
        val upstream = DeleteProbeBody(failRead = true)
        var cancellations = 0
        val body = AndroidComplaintSessionResponseBody(upstream, deleteBudget()) { cancellations++ }
        val sink = Buffer()
        try {
            assertFailsWith<IOException> { body.use { it.source().read(sink, 1) } }
            assertFails { body.source().read(sink, 1) }
            assertEquals(0L, sink.size)
            assertEquals(1, upstream.reads)
            assertEquals(1, upstream.closes)
            assertEquals(1, cancellations)
        } finally {
            body.close()
            sink.clear()
        }
    }

    @Test
    fun closeBeforeEofIncludingFailedSourceCloseStaysClosedAndCancelsOnlyOnce() {
        for (failClose in listOf(false, true)) {
            val upstream = DeleteProbeBody(failClose = failClose)
            var cancellations = 0
            val body = AndroidComplaintSessionResponseBody(upstream, deleteBudget()) { cancellations++ }
            val sink = Buffer()
            try {
                if (failClose) {
                    assertFailsWith<IOException> { body.source().close() }
                } else {
                    body.source().close()
                }
                assertFails { body.source().read(sink, 1) }
                body.close()
                assertEquals(0L, sink.size)
                assertEquals(0, upstream.reads)
                assertEquals(1, upstream.closes)
                assertEquals(1, cancellations)
            } finally {
                body.close()
                sink.clear()
            }
        }
    }

    private fun responseSubstitutions(): List<(Request) -> Request> =
        listOf(
            { it.newBuilder().method("POST", it.body).build() },
            { it.newBuilder().url("$CREATE_URL/$MUTATION_TEST_KEY").build() },
            { it.newBuilder().url("$DELETE_URL/content").build() },
            { it.newBuilder().header(Policy.IDEMPOTENCY_HEADER, "cccccccc-cccc-4ccc-8ccc-cccccccccccc").build() },
            { it.newBuilder().header("If-Match", "\"complaint-$MUTATION_TEST_PARENT-v2\"").build() },
            { it.newBuilder().method("DELETE", ByteArray(0).toRequestBody()).build() },
        )

    private fun withNativeResponse(
        change: (Request) -> Request = { it },
        headers: List<Pair<String, String>> = emptyList(),
        block: (Call, DeleteProbeBody) -> Unit,
    ) {
        val resources = AndroidComplaintSessionResources()
        val upstream = DeleteProbeBody()
        try {
            val target = assertNotNull(androidComplaintMutationTarget(Url(CREATE_URL)))
            val client =
                OkHttpClient
                    .Builder()
                    .complaintMutationPolicy(target, resources)
                    .addInterceptor { chain -> nativeResponse(change(chain.request()), upstream, headers) }
                    .build()
            block(client.newCall(deleteRequest()), upstream)
        } finally {
            upstream.close()
            resources.close()
        }
    }

    private fun deleteRequest(): Request =
        Request
            .Builder()
            .url(DELETE_URL)
            .method("DELETE", ByteArray(0).toRequestBody())
            .apply {
                mutationTestHeaders(ComplaintMutationRoute.OWNER_DELETE).forEach { (name, value) -> header(name, value) }
            }.build()

    private fun nativeResponse(
        request: Request,
        upstream: DeleteProbeBody,
        headers: List<Pair<String, String>>,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("Synthetic owner-delete")
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .body(upstream)
            .build()

    private fun deleteBudget(): ComplaintReceiveBudget =
        assertNotNull(
            ComplaintMutationReceiveBudget.checked(
                ComplaintMutationRoute.OWNER_DELETE,
                204,
                ComplaintMutationResponseHeaders(emptyList(), emptyList(), emptyList(), emptyList()),
            ),
        )

    private companion object {
        const val CREATE_URL = "https://example.invalid/base_1/v2/api/v1/complaints"
        const val DELETE_URL = "$CREATE_URL/$MUTATION_TEST_PARENT"
    }
}

private class DeleteProbeBody(
    actual: Int = 0,
    private val failRead: Boolean = false,
    private val failClose: Boolean = false,
) : ResponseBody() {
    var reads = 0
        private set
    var closes = 0
        private set
    private val bytes =
        object : ForwardingSource(Buffer().write(ByteArray(actual))) {
            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                reads++
                if (failRead) throw IOException("Synthetic EOF failure")
                return super.read(sink, byteCount)
            }

            override fun close() {
                closes++
                super.close()
                if (failClose) throw IOException("Synthetic close failure")
            }
        }.buffer()

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = 0

    override fun source(): BufferedSource = bytes
}
