package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintMutationReceiveGuardTest {
    @Test
    fun routeAndMediaSelectThirtyTwoOrSixteenKibibytesBeforeAnyCallbackIsForwarded() {
        val cases =
            listOf(
                Boundary(
                    ComplaintMutationRoute.CREATE,
                    CREATED,
                    "application/json",
                    Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES,
                ),
                Boundary(ComplaintMutationRoute.STATUS, OK, "application/json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
                Boundary(
                    ComplaintMutationRoute.CREATE,
                    CONFLICT,
                    "application/problem+json",
                    Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                ),
                Boundary(
                    ComplaintMutationRoute.CREATE,
                    CREATED,
                    "application/problem+json",
                    Policy.MAX_STATUS_OR_PROBLEM_BYTES,
                ),
            )
        cases.forEach { case ->
            listOf(false, true).forEach { overflow ->
                withIosMutationGuard { fixture ->
                    val task = fixture.task(iosMutationTestRequest(case.route))
                    val headers = mapOf("Content-Type" to case.media, "Content-Length" to case.cap.toString())
                    assertTrue(fixture.admit(task, headers, iosMutationTestUrl(case.route), case.status))
                    fixture.receive(task, case.cap)
                    if (overflow) fixture.receive(task, 1)
                    fixture.complete(task)
                    assertEquals(case.cap.toULong(), fixture.callbacks.forwardedBytes)
                    val error = fixture.callbacks.errors.single()
                    if (overflow) assertNotNull(error) else assertNull(error)
                    assertEquals(0, fixture.guard.activeTaskCount)
                }
            }
        }
    }

    @Test
    fun responseCannotChangeTheOriginalRouteOrGrantTheStatusRequestTheCreateBudget() {
        ComplaintMutationRoute.entries.forEach { route ->
            withIosMutationGuard { fixture ->
                val task = fixture.task(iosMutationTestRequest(route))
                val other =
                    if (route == ComplaintMutationRoute.CREATE) {
                        ComplaintMutationRoute.STATUS
                    } else {
                        ComplaintMutationRoute.CREATE
                    }
                assertFalse(
                    fixture.admit(
                        task,
                        mapOf("Content-Type" to "application/json"),
                        iosMutationTestUrl(other),
                        CREATED,
                    ),
                )
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
            }
        }
        withIosMutationGuard { fixture ->
            val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.STATUS))
            val headers =
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Length" to Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES.toString(),
                )
            assertFalse(fixture.admit(task, headers, IOS_MUTATION_STATUS_URL, CREATED))
            assertEquals(0uL, fixture.callbacks.forwardedBytes)
            assertNotNull(fixture.callbacks.errors.single())
        }
    }

    private data class Boundary(
        val route: ComplaintMutationRoute,
        val status: Int,
        val media: String,
        val cap: Int,
    )

    private companion object {
        const val CREATED = 201
        const val OK = 200
        const val CONFLICT = 409
    }
}
