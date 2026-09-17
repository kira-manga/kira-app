package me.manga.kira.data.remote.complaint

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class AndroidComplaintDeletionBodyTest {
    @Test
    fun exactFourKibibyteSnapshotIsOneShotAndDispatchNeverReinvokesTheCallerWriter() {
        val input = SyntheticDeletionBody(Policy.MAX_REQUEST_BYTES.toLong(), Policy.MAX_REQUEST_BYTES)
        val snapshot = boundedComplaintDeletionBody(input)
        assertTrue(snapshot.isOneShot())
        assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), snapshot.contentLength())
        val output = Buffer()
        snapshot.writeTo(output)
        assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), output.size)
        assertEquals(1, input.writes)
        output.clear()
    }

    @Test
    fun absentOversizedDuplexOrDishonestBodiesCannotReachNativeHttp() {
        listOf(-1L, 0L, Policy.MAX_REQUEST_BYTES + 1L).forEach { declared ->
            val input = SyntheticDeletionBody(declared, 1)
            assertFailsWith<IOException> { boundedComplaintDeletionBody(input) }
            assertEquals(0, input.writes)
        }
        listOf(0, 2, Policy.MAX_REQUEST_BYTES + 1).forEach { actual ->
            val input = SyntheticDeletionBody(1, actual)
            assertFailsWith<IOException> { boundedComplaintDeletionBody(input) }
            assertEquals(1, input.writes)
        }
        val duplex = SyntheticDeletionBody(1, 1, duplex = true)
        assertFailsWith<IOException> { boundedComplaintDeletionBody(duplex) }
        assertEquals(0, duplex.writes)
    }
}

private class SyntheticDeletionBody(
    private val declared: Long,
    private val actual: Int,
    private val duplex: Boolean = false,
) : RequestBody() {
    var writes = 0
        private set

    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = declared

    override fun isDuplex(): Boolean = duplex

    override fun writeTo(sink: BufferedSink) {
        writes += 1
        sink.write(ByteArray(actual))
    }
}
