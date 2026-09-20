package me.manga.kira.data.remote.complaint

import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class AndroidComplaintHistoryBodyTest {
    @Test
    fun sharedNativeSourceNeverForwardsTheHistoryOverrunByteAndCancelsOnce() {
        val cap = ComplaintHistoryReceiveBudget.MAX_BYTES
        val budget =
            assertNotNull(ComplaintHistoryReceiveBudget.checked(SUCCESS_STATUS, emptyList(), emptyList(), emptyList()))
        val upstream = ByteArray(cap + 1).toResponseBody()
        var cancellations = 0
        val body = AndroidComplaintSessionResponseBody(upstream, budget) { cancellations++ }
        val sink = Buffer()
        try {
            repeat(cap / READ_BYTES) {
                assertEquals(READ_BYTES.toLong(), body.source().read(sink, READ_BYTES.toLong()))
            }
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

    private companion object {
        const val SUCCESS_STATUS = 200L
        const val READ_BYTES = 8_192
    }
}
