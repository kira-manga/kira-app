package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Existing real-task callback fixture only: no network task resume or Foundation replay/drain claim. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
@Suppress("MagicNumber") // Exact route/status/media boundaries and callback counts.
class IosComplaintMutationEditReceiveGuardTest {
    @Test
    fun edit200JsonAloneGetsThirtyTwoKiBWithStickyOverflowAndExactlyOneCompletion() {
        val cases = responseBoundaries()
        for (case in cases) {
            for (overflow in listOf(false, true)) {
                withIosMutationGuard { fixture ->
                    val task = fixture.task(iosMutationTestRequest(case.route))
                    val headers = mapOf("Content-Type" to case.media, "Content-Length" to case.cap.toString())
                    val url = iosMutationTestUrl(case.route)
                    assertTrue(fixture.admit(task, headers, url, case.status))
                    fixture.receive(task, case.cap)
                    if (overflow) fixture.receive(task, 1)
                    fixture.complete(task)
                    fixture.receive(task, 1)
                    fixture.complete(task)
                    assertFalse(fixture.admit(task, headers, url, case.status))
                    assertEquals(case.cap.toULong(), fixture.callbacks.forwardedBytes)
                    val error = fixture.callbacks.errors.single()
                    if (overflow) assertNotNull(error) else assertNull(error)
                    assertEquals(0, fixture.guard.activeTaskCount)
                }
            }
        }
    }

    private fun responseBoundaries(): List<Boundary> =
        listOf(
            Boundary(ComplaintMutationRoute.EDIT, 200, "application/json", Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES),
            Boundary(ComplaintMutationRoute.EDIT, 412, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
            Boundary(ComplaintMutationRoute.EDIT, 201, "application/json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
            Boundary(
                ComplaintMutationRoute.EDIT,
                200,
                "application/json, application/json",
                Policy.MAX_STATUS_OR_PROBLEM_BYTES,
            ),
            Boundary(ComplaintMutationRoute.STATUS, 200, "application/json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
        )

    @Test
    fun declaredEditOverrunBadCodingAndAmbiguousFramingRejectBeforeAnyPrefixIsForwarded() {
        val cap = Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES
        val invalid =
            listOf(
                JSON_HEADERS + ("Content-Length" to "${cap + 1}"),
                JSON_HEADERS + ("Content-Encoding" to "gzip"),
                JSON_HEADERS + ("Content-Length" to "1, 1"),
                JSON_HEADERS + mapOf("Content-Length" to "1", "Transfer-Encoding" to "chunked"),
                mapOf("Content-Type" to "application/json, application/json", "Content-Length" to "$cap"),
            )
        invalid.forEach { headers ->
            withIosMutationGuard { fixture ->
                val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.EDIT))
                assertFalse(fixture.admit(task, headers, EDIT_URL))
                fixture.receive(task, 1)
                fixture.complete(task)
                fixture.receive(task, 1)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun editShortDeclaredEofCannotSucceedOrResurrectAndItsSlotAcceptsTheNextExplicitStatus() =
        withIosMutationGuard { fixture ->
            val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.EDIT))
            assertTrue(fixture.admit(task, JSON_HEADERS + ("Content-Length" to "2"), EDIT_URL))
            fixture.receive(task, 1)
            fixture.complete(task)
            fixture.receive(task, 1)
            fixture.complete(task)
            assertEquals(1uL, fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
            assertEquals(0, fixture.guard.activeTaskCount)
            val status = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.STATUS))
            assertTrue(fixture.admit(status, JSON_HEADERS + ("Content-Length" to "1"), IOS_MUTATION_STATUS_URL))
            fixture.receive(status, 1)
            fixture.complete(status)
            assertEquals(2uL, fixture.callbacks.forwardedBytes)
            assertEquals(2, fixture.callbacks.errors.size)
            assertNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun editCancellationAndOwnerCloseKeepTheSharedNativeSlotAndLateCallbacksFenced() =
        withIosMutationGuard { fixture ->
            val first = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.EDIT))
            assertTrue(fixture.admit(first, JSON_HEADERS, EDIT_URL))
            fixture.receive(first, LARGE_PREFIX)
            val contendingStatus = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.STATUS))
            assertFalse(fixture.admit(contendingStatus, JSON_HEADERS, IOS_MUTATION_STATUS_URL))
            assertEquals(1, fixture.guard.activeTaskCount)
            val cancelled = NSError(NSURLErrorDomain, NSURLErrorCancelled, null)
            fixture.complete(first, cancelled)
            assertSame(cancelled, fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
            fixture.receive(first, 1)
            val next = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.EDIT))
            assertTrue(fixture.admit(next, JSON_HEADERS, EDIT_URL))
            fixture.receive(next, LARGE_PREFIX)
            fixture.guard.close(fixture.session)
            fixture.guard.close(fixture.session)
            fixture.receive(next, 1)
            fixture.complete(next)
            assertFalse(fixture.admit(next, JSON_HEADERS, EDIT_URL))
            assertFails { fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.EDIT)) }
            assertEquals((LARGE_PREFIX * 2).toULong(), fixture.callbacks.forwardedBytes)
            assertEquals(3, fixture.callbacks.errors.size)
            assertNotNull(fixture.callbacks.errors.last())
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun delivered503And421KeepExistingMutationCallbackSemanticsNotTheHistoryGetGuard() {
        for (status in listOf(503, 421)) {
            withIosMutationGuard { fixture ->
                // A delivered response is observable here; this says nothing about hidden Foundation replay.
                val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.EDIT))
                val headers = mapOf("Content-Type" to "application/problem+json", "Retry-After" to "0")
                assertTrue(fixture.admit(task, headers, EDIT_URL, status))
                fixture.receive(task, Policy.MAX_STATUS_OR_PROBLEM_BYTES)
                fixture.complete(task)
                fixture.receive(task, 1)
                assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES.toULong(), fixture.callbacks.forwardedBytes)
                assertNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    private data class Boundary(
        val route: ComplaintMutationRoute,
        val status: Int,
        val media: String,
        val cap: Int,
    )

    private companion object {
        const val EDIT_URL = "$IOS_MUTATION_CREATE_URL/$MUTATION_TEST_PARENT/content"
        const val LARGE_PREFIX = 16 * 1_024 + 1
        val JSON_HEADERS = mapOf("Content-Type" to "application/json")
    }
}
