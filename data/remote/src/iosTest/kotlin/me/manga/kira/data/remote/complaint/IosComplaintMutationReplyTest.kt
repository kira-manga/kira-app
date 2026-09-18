package me.manga.kira.data.remote.complaint

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Real Foundation requests/tasks and the existing driven delegate; no resumed network task or Darwin queue claim. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintMutationReplyTest {
    @Test
    fun nativeReplyPreparationAcceptsCanonicalNoticeParentButNotMissingKeyPreconditionOrOversizedBody() =
        withIosMutationGuard { fixture ->
            val replyUrl = replyPolicyUrl(IOS_MUTATION_CREATE_URL, REPLY_POLICY_NOTICE)
            fixture.guard.prepare(
                iosMutationTestRequest(ComplaintMutationRoute.REPLY, Policy.MAX_REQUEST_BYTES, replyUrl),
            )
            assertFails {
                fixture.guard.prepare(
                    iosMutationTestRequest(ComplaintMutationRoute.REPLY, url = replyUrl).apply {
                        setValue(null, Policy.IDEMPOTENCY_HEADER)
                    },
                )
            }
            assertFails {
                fixture.guard.prepare(
                    iosMutationTestRequest(ComplaintMutationRoute.REPLY, url = replyUrl).apply {
                        setValue("\"synthetic\"", "If-Match")
                    },
                )
            }
            for (size in listOf(0, Policy.MAX_REQUEST_BYTES + 1)) {
                assertFails {
                    fixture.guard.prepare(iosMutationTestRequest(ComplaintMutationRoute.REPLY, size, replyUrl))
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosMutationTestRequest(ComplaintMutationRoute.REPLY, url = "$replyUrl?ignored=true"),
                )
            }
            assertEquals(0, fixture.guard.activeTaskCount)
        }

    @Test
    fun responseForAnotherCanonicalParentIsRejectedBeforeForwardingEvenThoughBothRoutesAreReply() {
        val parents =
            listOf(
                REPLY_POLICY_PARENT to REPLY_POLICY_NOTICE,
                REPLY_POLICY_NOTICE to REPLY_POLICY_PARENT,
            )
        for ((sentParent, responseParent) in parents) {
            withIosMutationGuard { fixture ->
                val originalUrl = replyPolicyUrl(IOS_MUTATION_CREATE_URL, sentParent)
                val responseUrl = replyPolicyUrl(IOS_MUTATION_CREATE_URL, responseParent)
                val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.REPLY, url = originalUrl))
                assertFalse(
                    fixture.admit(
                        task,
                        mapOf("Content-Type" to "application/json", "Content-Length" to "1"),
                        responseUrl,
                        201,
                    ),
                )
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(0uL, fixture.callbacks.forwardedBytes)
                assertNotNull(fixture.callbacks.errors.single())
                assertEquals(0, fixture.guard.activeTaskCount)
            }
        }
    }

    @Test
    fun reply201And404Or409UseTheirOwnNativeReceiveCapsWithStickyOverflowAndOneCompletion() {
        val cases =
            listOf(
                Boundary(201, "application/json", Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES),
                Boundary(404, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
                Boundary(409, "application/problem+json", Policy.MAX_STATUS_OR_PROBLEM_BYTES),
            )
        for (case in cases) {
            for (overflow in listOf(false, true)) {
                withIosMutationGuard { fixture ->
                    val replyUrl = replyPolicyUrl(IOS_MUTATION_CREATE_URL)
                    val task = fixture.task(iosMutationTestRequest(ComplaintMutationRoute.REPLY, url = replyUrl))
                    val headers = mapOf("Content-Type" to case.media, "Content-Length" to case.cap.toString())
                    assertTrue(fixture.admit(task, headers, replyUrl, case.status))
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

    private data class Boundary(
        val status: Int,
        val media: String,
        val cap: Int,
    )
}
