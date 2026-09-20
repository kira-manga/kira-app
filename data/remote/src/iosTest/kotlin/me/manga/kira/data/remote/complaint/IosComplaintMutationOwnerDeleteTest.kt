package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPBodyStream
import platform.Foundation.HTTPMethod
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSInputStream
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.valueForHTTPHeaderField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Real Foundation requests/tasks with driven callbacks only; no task is resumed. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
@Suppress("MagicNumber") // Direct204 and exact empty native-body cells.
class IosComplaintMutationOwnerDeleteTest {
    @Test
    fun supplierMergedDeleteAdmitsNilAndEmptyNSDataWithoutRepresentationHeaders() {
        val create = "https://example.invalid:9443/base_1/v2" + Policy.CREATE_PATH
        val delete = "$create/$MUTATION_TEST_PARENT"
        listOf(false, true).forEach { emptyData ->
            withIosMutationGuard(create) { fixture ->
                val request = supplierDeleteRequest(delete, emptyData)
                fixture.guard.prepare(request)
                assertEquals("DELETE", request.HTTPMethod)
                assertEquals(delete, request.URL?.absoluteString)
                assertEquals(if (emptyData) 0uL else null, request.HTTPBody?.length)
                assertDeleteHeaders(request)
                assertFalse(request.HTTPShouldHandleCookies)
                assertEquals(NSURLRequestReloadIgnoringLocalCacheData, request.cachePolicy)
                val task = fixture.task(request)
                assertTrue(fixture.admit(task, url = delete, status = 204))
                fixture.receive(task, 0)
                fixture.complete(task)
                assertTrue(fixture.callbacks.forwarded.isEmpty())
                assertNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun nativeDeleteRejectsPositiveDataStreamsAndNoncanonicalFramingWithoutRelaxingJsonRoutes() =
        withIosMutationGuard { fixture ->
            invalidBodyOrFraming().forEach { change ->
                val request = iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE).apply(change)
                assertFails { fixture.guard.prepare(request) }
            }
            fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE))
            val maximum =
                iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE).apply {
                    setValue("0", "Content-Length")
                    setValue("\"complaint-$MUTATION_TEST_PARENT-v${Long.MAX_VALUE}\"", "If-Match")
                }
            fixture.guard.prepare(maximum)
            ComplaintMutationRoute.entries.filterNot { it == ComplaintMutationRoute.OWNER_DELETE }.forEach { route ->
                assertFails { fixture.guard.prepare(iosMutationTestRequest(route).apply { setHTTPBody(null) }) }
                assertFails { fixture.guard.prepare(iosMutationTestRequest(route, size = 0)) }
            }
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun deliveredResponseRechecksTheActualDeleteMethodKeyTagAndBody() {
        listOf<NSMutableURLRequest.() -> Unit>(
            { setHTTPMethod("POST") },
            { setValue(null, Policy.IDEMPOTENCY_HEADER) },
            { setValue(MUTATION_TEST_PARENT, Policy.IDEMPOTENCY_HEADER) },
            { setValue("$MUTATION_TEST_KEY, $MUTATION_TEST_KEY", Policy.IDEMPOTENCY_HEADER) },
            { setValue(null, "If-Match") },
            { setValue("W/$MUTATION_TEST_PRECONDITION", "If-Match") },
            { setValue("\"complaint-$MUTATION_TEST_KEY-v1\"", "If-Match") },
            { setHTTPBody(iosSessionTestData(1)) },
            { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(0))) },
        ).forEach { change ->
            withIosMutationGuard { fixture ->
                val request = iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE).apply(change)
                assertFails { fixture.guard.prepare(request) }
                assertRejectedResponse(fixture, request, DELETE_URL)
            }
        }
    }

    @Test
    fun responseForAnotherTargetOrMutationRouteNeverForwardsOrResurrects() {
        listOf(
            DELETE_URL.replace(MUTATION_TEST_PARENT, MUTATION_TEST_KEY),
            "$DELETE_URL/content",
            "$DELETE_URL/replies",
            IOS_MUTATION_CREATE_URL,
            IOS_MUTATION_STATUS_URL,
            "$DELETE_URL?version=1",
            DELETE_URL.replace("example.invalid", "elsewhere.invalid"),
        ).forEach { url ->
            withIosMutationGuard { fixture ->
                assertRejectedResponse(fixture, iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE), url)
            }
        }
    }

    private fun invalidBodyOrFraming(): List<NSMutableURLRequest.() -> Unit> =
        listOf(
            { setHTTPBody(iosSessionTestData(1)) },
            { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(0))) },
            { setValue("application/json", "Content-Type") },
            { setValue("identity", "Content-Encoding") },
            { setValue("chunked", "Transfer-Encoding") },
            { setValue("", "Content-Length") },
            { setValue("00", "Content-Length") },
            { setValue("0, 0", "Content-Length") },
            { setValue("1", "Content-Length") },
        )

    private fun supplierDeleteRequest(
        url: String,
        emptyData: Boolean,
    ): NSMutableURLRequest =
        iosMutationTestRequest(ComplaintMutationRoute.OWNER_DELETE, url = url).apply {
            if (emptyData) setHTTPBody(iosSessionTestData(0))
            mutationTestEngineHeaders(ComplaintMutationRoute.OWNER_DELETE).forEach { (name, value) ->
                setValue(value, name)
            }
        }

    private fun assertDeleteHeaders(request: NSMutableURLRequest) {
        assertEquals(MUTATION_TEST_KEY, request.valueForHTTPHeaderField(Policy.IDEMPOTENCY_HEADER))
        assertEquals(MUTATION_TEST_PRECONDITION, request.valueForHTTPHeaderField("If-Match"))
        assertEquals(MUTATION_TEST_AUTHORIZATION, request.valueForHTTPHeaderField("Authorization"))
        assertEquals("application/json, application/problem+json", request.valueForHTTPHeaderField("Accept"))
        assertEquals("identity", request.valueForHTTPHeaderField("Accept-Encoding"))
        assertEquals("no-store, no-transform", request.valueForHTTPHeaderField("Cache-Control"))
        assertEquals("ktor-client", request.valueForHTTPHeaderField("User-Agent"))
        assertEquals("0", request.valueForHTTPHeaderField("Content-Length"))
        listOf("Content-Type", "Content-Encoding", "Transfer-Encoding", "Cookie", "Proxy-Authorization")
            .forEach { name -> assertNull(request.valueForHTTPHeaderField(name)) }
        assertNull(request.HTTPBodyStream)
    }

    private fun assertRejectedResponse(
        fixture: IosSessionGuardFixture,
        request: NSMutableURLRequest,
        responseUrl: String,
    ) {
        val task = fixture.task(request)
        assertFalse(fixture.admit(task, url = responseUrl, status = 204))
        fixture.receive(task, 0)
        fixture.receive(task, 1)
        fixture.complete(task)
        fixture.receive(task, 1)
        fixture.complete(task)
        assertTrue(fixture.callbacks.forwarded.isEmpty())
        assertNotNull(fixture.callbacks.errors.single())
        assertEquals(0, fixture.guard.activeTaskCount)
    }

    private companion object {
        const val DELETE_URL = "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT"
    }
}
