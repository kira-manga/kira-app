package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidComplaintDetailResponseGuardTest {
    @Test
    fun actualInterceptorRejectsDifferentResourceOrListResponseAndClosesItBeforeForwarding() {
        val detail = "$ANDROID_HISTORY_TEST_URL/$DETAIL_ID"
        val responses = listOf(detail, "$ANDROID_HISTORY_TEST_URL/$OTHER_ID", "$ANDROID_HISTORY_TEST_URL?limit=50")
        for (responseUrl in responses) {
            assertResponseTarget(detail, responseUrl)
        }
    }

    @Test
    fun nativeBodyNeverForwardsTheDetailOverrunByteAndCancelsOnlyOnce() {
        val cap = ComplaintHistoryReceiveBudget.MAX_DETAIL_BYTES
        val budget =
            assertNotNull(
                ComplaintHistoryReceiveBudget.checkedDetail(
                    200,
                    listOf("application/json"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
            )
        var cancellations = 0
        val body = AndroidComplaintSessionResponseBody(ByteArray(cap + 1).toResponseBody(), budget) { cancellations++ }
        val sink = Buffer()
        try {
            repeat(cap / READ_BYTES) { assertEquals(READ_BYTES.toLong(), body.source().read(sink, READ_BYTES.toLong())) }
            assertFails { body.source().read(sink, 1) }
            assertEquals(cap.toLong(), sink.size)
            assertEquals(cap, budget.receivedBytes)
            assertEquals(1, cancellations)
            body.close()
            assertEquals(1, cancellations)
        } finally {
            body.close()
            sink.clear()
        }
    }

    private fun assertResponseTarget(
        requestUrl: String,
        responseUrl: String,
    ) {
        val resources = AndroidComplaintSessionResources()
        val upstream = ClosingDetailBody()
        try {
            val target = assertNotNull(androidComplaintHistoryTarget(Url(ANDROID_HISTORY_TEST_URL)))
            // Synthetic downstream response through the real guard; no redirect or live-origin claim.
            val client =
                OkHttpClient
                    .Builder()
                    .complaintHistoryPolicy(target, resources)
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request().newBuilder().url(responseUrl).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic detail")
                            .header("Content-Type", "application/json")
                            .body(upstream)
                            .build()
                    }.build()
            val request =
                Request
                    .Builder()
                    .url(requestUrl)
                    .header("Authorization", HISTORY_TEST_AUTHORIZATION)
                    .header("Accept-Encoding", "identity")
                    .build()
            val call = client.newCall(request)
            if (requestUrl == responseUrl) {
                call.execute().use { assertEquals("{}", it.body.string()) }
                assertFalse(call.isCanceled())
            } else {
                assertFails { call.execute().close() }
                assertTrue(call.isCanceled())
            }
            assertEquals(1, upstream.closes)
        } finally {
            upstream.close()
            resources.close()
        }
    }

    private companion object {
        const val DETAIL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val READ_BYTES = 8_192
    }
}

internal class ClosingDetailBody : ResponseBody() {
    var closes = 0
        private set
    private val bytes =
        object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() {
                closes++
                super.close()
            }
        }.buffer()

    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = 2L

    override fun source(): BufferedSource = bytes
}
