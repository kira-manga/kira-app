package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Fixed native response cells and byte-count assertions.
class AndroidComplaintMutationEditResponseGuardTest {
    @Test
    fun actualInterceptorRequiresTheExactResponseMethodAndContentIdAndClosesRejectedBodies() {
        val responses =
            listOf(
                EDIT_URL to "PATCH",
                EDIT_URL to "POST",
                "$CREATE_URL/$MUTATION_TEST_KEY/content" to "PATCH",
                "$CREATE_URL/$MUTATION_TEST_PARENT/replies" to "PATCH",
                CREATE_URL to "PATCH",
                "$EDIT_URL?version=1" to "PATCH",
            )
        for ((responseUrl, responseMethod) in responses) {
            assertResponseBinding(responseUrl, responseMethod)
        }
    }

    @Test
    fun editBodyRejectsTheOverrunByteAndShortDeclaredEofWithStickyFailureAndOneCancellation() {
        val cap = Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES
        for (overflow in listOf(true, false)) {
            val budget = editReceiveBudget(overflow)
            var cancellations = 0
            val upstream = ByteArray(if (overflow) cap + 1 else 1).toResponseBody()
            val body = AndroidComplaintSessionResponseBody(upstream, budget) { cancellations++ }
            val sink = Buffer()
            try {
                if (overflow) {
                    repeat(cap / 8_192) { assertEquals(8_192L, body.source().read(sink, 8_192)) }
                } else {
                    assertEquals(1L, body.source().read(sink, 1))
                }
                assertFailsWith<IOException> { body.source().read(sink, 1) }
                assertFailsWith<IOException> { body.source().read(sink, 1) }
                val accepted = if (overflow) cap else 1
                assertEquals(accepted.toLong(), sink.size)
                assertEquals(accepted, budget.receivedBytes)
                assertEquals(1, cancellations)
                body.close()
                body.close()
                assertEquals(1, cancellations)
            } finally {
                body.close()
                sink.clear()
            }
        }
    }

    private fun assertResponseBinding(responseUrl: String, responseMethod: String) {
        val resources = AndroidComplaintSessionResources()
        val upstream = ClosingDetailBody()
        var dispatches = 0
        try {
            val target = assertNotNull(androidComplaintMutationTarget(Url(CREATE_URL)))
            // Existing synthetic downstream pattern: the real interceptor runs, with no live-origin claim.
            val client =
                OkHttpClient.Builder()
                    .complaintMutationPolicy(target, resources)
                    .addInterceptor { chain ->
                        dispatches++
                        editResponse(chain.request(), responseUrl, responseMethod, upstream)
                    }.build()
            val request = editRequest()
            val call = client.newCall(request)
            if (responseUrl == EDIT_URL && responseMethod == "PATCH") {
                call.execute().use { assertEquals("{}", it.body.string()) }
                assertFalse(call.isCanceled())
            } else {
                assertFailsWith<IOException> { call.execute().close() }
                assertTrue(call.isCanceled())
            }
            assertEquals(1, dispatches)
            assertEquals(1, upstream.closes)
        } finally {
            upstream.close()
            resources.close()
        }
    }

    private fun editReceiveBudget(overflow: Boolean): ComplaintMutationReceiveBudget {
        val headers =
            ComplaintMutationResponseHeaders(
                listOf("application/json"),
                emptyList(),
                if (overflow) emptyList() else listOf("2"),
                emptyList(),
            )
        return assertNotNull(ComplaintMutationReceiveBudget.checked(ComplaintMutationRoute.EDIT, 200, headers))
    }

    private fun editRequest(): Request =
        Request
            .Builder()
            .url(EDIT_URL)
            .method("PATCH", "{}".toRequestBody("application/json".toMediaType()))
            .apply {
                mutationTestHeaders(ComplaintMutationRoute.EDIT).forEach { (name, value) ->
                    header(name, value)
                }
            }.build()

    private fun editResponse(
        outgoing: Request,
        responseUrl: String,
        responseMethod: String,
        upstream: ClosingDetailBody,
    ): Response {
        assertTrue(assertNotNull(outgoing.body).isOneShot())
        val delivered = outgoing.newBuilder().url(responseUrl).method(responseMethod, outgoing.body).build()
        return Response
            .Builder()
            .request(delivered)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Synthetic edit")
            .header("Content-Type", "application/json")
            .body(upstream)
            .build()
    }

    private companion object {
        const val CREATE_URL = "https://example.invalid/base_1/v2/api/v1/complaints"
        const val EDIT_URL = "$CREATE_URL/$MUTATION_TEST_PARENT/content"
    }
}
