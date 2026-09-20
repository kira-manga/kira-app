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
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class AndroidComplaintMutationBodyTest {
    @Test
    fun exactSnapshotIsOneShotAndTheCallerBodyWriterIsNotUsedForDispatch() {
        val input = SyntheticMutationBody(Policy.MAX_REQUEST_BYTES.toLong(), Policy.MAX_REQUEST_BYTES)
        val body = boundedComplaintMutationBody(input)
        assertTrue(body.isOneShot())
        assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), body.contentLength())
        val output = Buffer()
        body.writeTo(output)
        assertEquals(Policy.MAX_REQUEST_BYTES.toLong(), output.size)
        assertEquals(1, input.writes)
        output.clear()
    }

    @Test
    fun unknownOversizedAndLyingDeclarationsCannotProduceARequestBody() {
        listOf(-1L, 0L, Policy.MAX_REQUEST_BYTES + 1L).forEach { declared ->
            val input = SyntheticMutationBody(declared, 1)
            assertFailsWith<IOException> { boundedComplaintMutationBody(input) }
            assertEquals(0, input.writes)
        }
        listOf(0, 2, Policy.MAX_REQUEST_BYTES + 1).forEach { actual ->
            val input = SyntheticMutationBody(1, actual)
            assertFailsWith<IOException> { boundedComplaintMutationBody(input) }
            assertEquals(1, input.writes)
        }
    }
}

private class SyntheticMutationBody(
    private val declared: Long,
    private val actual: Int,
) : RequestBody() {
    var writes = 0
        private set

    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = declared

    override fun writeTo(sink: BufferedSink) {
        writes += 1
        sink.write(ByteArray(actual))
    }
}
