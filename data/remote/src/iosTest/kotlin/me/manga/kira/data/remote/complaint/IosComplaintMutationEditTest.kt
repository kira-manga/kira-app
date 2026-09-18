package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPBody
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
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Foundation requests/tasks and driven callbacks only; no task is resumed or live replay/drain measured. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintMutationEditTest {
    @Test
    fun nativeEditPreparationKeepsExactPrefixPatchNonV4TargetKeyAndCanonicalPrecondition() {
        val create = "https://example.invalid:9443/base_1/v2" + Policy.CREATE_PATH
        val edit = "$create/$MUTATION_TEST_PARENT/content"
        withIosMutationGuard(create) { fixture ->
            val request = iosMutationTestRequest(ComplaintMutationRoute.EDIT, Policy.MAX_REQUEST_BYTES, edit)
            fixture.guard.prepare(request)
            assertEquals("PATCH", request.HTTPMethod)
            assertEquals(edit, request.URL?.absoluteString)
            assertEquals(Policy.MAX_REQUEST_BYTES.toULong(), request.HTTPBody?.length)
            assertEquals(MUTATION_TEST_KEY, request.valueForHTTPHeaderField(Policy.IDEMPOTENCY_HEADER))
            assertEquals(MUTATION_TEST_PRECONDITION, request.valueForHTTPHeaderField("If-Match"))
            assertEquals(MUTATION_TEST_AUTHORIZATION, request.valueForHTTPHeaderField("Authorization"))
            assertEquals("application/json", request.valueForHTTPHeaderField("Content-Type"))
            assertEquals("identity", request.valueForHTTPHeaderField("Accept-Encoding"))
            assertEquals("no-store, no-transform", request.valueForHTTPHeaderField("Cache-Control"))
            assertFalse(request.HTTPShouldHandleCookies)
            assertEquals(NSURLRequestReloadIgnoringLocalCacheData, request.cachePolicy)
            assertNull(request.valueForHTTPHeaderField("Cookie"))
            assertNull(request.valueForHTTPHeaderField("Proxy-Authorization"))
            listOf(
                "$edit?version=1",
                "$edit/",
                edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_PARENT.uppercase()),
                edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_KEY),
                edit.replace("/base_1/v2", ""),
                edit.replace(":9443", ""),
                edit.replace("example.invalid", "elsewhere.invalid"),
            ).forEach { url ->
                assertFails { fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.EDIT, url = url)) }
            }
            assertEquals(0, fixture.guard.activeTaskCount)
        }
    }

    @Test
    fun editNativeAdmissionRejectsMissingOrAmbiguousAuthorityAndNeverWidensOldPostRoutes() =
        withIosMutationGuard { fixture ->
            listOf(null, MUTATION_TEST_PARENT, "$MUTATION_TEST_KEY, $MUTATION_TEST_KEY").forEach { key ->
                assertFails {
                    fixture.guard.prepare(
                        iosMutationTestRequest(ComplaintMutationRoute.EDIT).apply {
                            setValue(key, Policy.IDEMPOTENCY_HEADER)
                        },
                    )
                }
            }
            listOf(
                null,
                "W/$MUTATION_TEST_PRECONDITION",
                "*",
                "$MUTATION_TEST_PRECONDITION, $MUTATION_TEST_PRECONDITION",
                "\"complaint-$MUTATION_TEST_KEY-v1\"",
                "\"complaint-$MUTATION_TEST_PARENT-v01\"",
                "\"complaint-$MUTATION_TEST_PARENT-v9223372036854775808\"",
            ).forEach { tag ->
                assertFails {
                    fixture.guard.prepare(
                        iosMutationTestRequest(ComplaintMutationRoute.EDIT).apply { setValue(tag, "If-Match") },
                    )
                }
            }
            val invalid =
                listOf<NSMutableURLRequest.() -> Unit>(
                    { setHTTPMethod("POST") },
                    { setHTTPBody(null) },
                    { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1))) },
                    { setValue("2", "Content-Length") },
                    { setValue("text/plain", "Content-Type") },
                    { setValue("gzip", "Content-Encoding") },
                    { setValue("synthetic", "Cookie") },
                )
            invalid.forEach { change ->
                assertFails { fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.EDIT).apply(change)) }
            }
            listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)
                .forEach { route ->
                    assertFails {
                        fixture.guard.prepare(iosMutationTestRequest(route).apply { setHTTPMethod("PATCH") })
                    }
                    assertFails {
                        fixture.guard.prepare(
                            iosMutationTestRequest(route).apply { setValue(MUTATION_TEST_PRECONDITION, "If-Match") },
                        )
                    }
                }
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun responseForAnotherContentIdOrRouteIsRejectedBeforeAnyDataOrLateCallbackCanEscape() {
        val edit = iosMutationTestUrl(ComplaintMutationRoute.EDIT)
        listOf(
            edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_KEY),
            iosMutationTestUrl(ComplaintMutationRoute.REPLY),
            IOS_MUTATION_CREATE_URL,
            "$edit?version=1",
        ).forEach { url -> assertRejectedResponse(iosMutationTestRequest(ComplaintMutationRoute.EDIT), url) }
    }

    @Test
    fun deliveredResponseRechecksTheOriginalPatchKeyAndIfMatchNotJustItsUrl() {
        val invalid =
            listOf<NSMutableURLRequest.() -> Unit>(
                { setHTTPMethod("POST") },
                { setValue(null, Policy.IDEMPOTENCY_HEADER) },
                { setValue(null, "If-Match") },
                { setValue("\"complaint-$MUTATION_TEST_KEY-v1\"", "If-Match") },
            )
        invalid.forEach { change ->
            assertRejectedResponse(
                iosMutationTestRequest(ComplaintMutationRoute.EDIT).apply(change),
                iosMutationTestUrl(ComplaintMutationRoute.EDIT),
            )
        }
    }

    private fun assertRejectedResponse(
        request: NSMutableURLRequest,
        responseUrl: String,
    ) = withIosMutationGuard { fixture ->
        val task = fixture.task(request)
        assertFalse(fixture.admit(task, mapOf("Content-Type" to "application/json"), responseUrl))
        fixture.receive(task, 1)
        fixture.complete(task)
        fixture.receive(task, 1)
        fixture.complete(task)
        assertEquals(0uL, fixture.callbacks.forwardedBytes)
        assertNotNull(fixture.callbacks.errors.single())
        assertEquals(0, fixture.guard.activeTaskCount)
    }
}
