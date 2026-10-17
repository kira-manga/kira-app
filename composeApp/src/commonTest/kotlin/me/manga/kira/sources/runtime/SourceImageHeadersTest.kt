package me.manga.kira.sources.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.toRuntimeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceImageHeadersTest {
    private val source = SourceConfig(
        api = "fixture", language = "(EN)", baseUrl = "https://source.test", engine = "generic",
        headers = mapOf("Referer" to "https://source.test", "User-Agent" to "static"),
    )

    @Test
    fun capture_opt_out_survives_projection_and_never_reads_the_store() = runTest {
        val descriptor = source.copy(usesCapturedHeaders = false).toRuntimeDescriptor()
        assertFalse(descriptor.usesCapturedHeaders)
        for (current in listOf(descriptor, descriptor.copy(headers = emptyMap()), null)) {
            var reads = 0
            assertEquals(current?.headers.orEmpty(), sourceImageHeaders(current) {
                reads++
                mapOf("Cookie" to "must-not-read")
            })
            assertEquals(0, reads)
        }
        // A new snapshot opting out cannot retain the preceding captured Cookie/UA.
        val old = sourceImageHeaders(source.toRuntimeDescriptor()) { mapOf("Cookie" to "old-fixture", "User-Agent" to "captured") }
        assertTrue(old.containsKey("Cookie"))
        assertFalse(sourceImageHeaders(descriptor) { old }.containsKey("Cookie"))
    }

    @Test
    fun opted_in_capture_precedence_and_cancellation_remain_unchanged() = runTest {
        val descriptor = source.toRuntimeDescriptor()
        assertTrue(descriptor.usesCapturedHeaders)
        var reads = 0
        assertEquals(
            source.headers + mapOf("User-Agent" to "captured", "Cookie" to "fixture"),
            sourceImageHeaders(descriptor) {
                reads++
                mapOf("User-Agent" to "captured", "Cookie" to "fixture")
            },
        )
        assertEquals(1, reads)
        assertEquals(source.headers, sourceImageHeaders(descriptor) { throw IllegalStateException("Unavailable store") })
        val cancelled = CancellationException("fixture")
        assertSame(cancelled, assertFailsWith<CancellationException> { sourceImageHeaders(descriptor) { throw cancelled } })
    }
}
