package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSInputStream
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintDeletionEngineTest {
    @Test
    fun factoryKeepsTheExactDeploymentPrefixAndRejectsOtherRoutesBeforeAllocation() {
        listOf(
            IOS_SESSION_TEST_URL,
            IOS_ENROLLMENT_TEST_URL,
            "$IOS_DELETION_URL?key=synthetic",
            "$IOS_DELETION_URL/",
            "https://user@example.invalid/api/v1/installations/delete-all",
            "https://example.invalid/bad/../api/v1/installations/delete-all",
        ).forEach { value ->
            val owner = createIosComplaintDeletionEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
        val url = "https://example.invalid:9443/base_1/v2" + Policy.PATH
        val target = assertNotNull(iosComplaintDeletionTarget(Url(url)))
        assertTrue(target.matches(url))
        assertFalse(target.matches(IOS_DELETION_URL))
    }

    @Test
    fun onlyExactPostWithARealBoundedBodyAndTruthfulLengthPassesPreparation() =
        withIosDeletionGuard { fixture ->
            fixture.guard.prepare(iosDeletionTestRequest(Policy.MAX_REQUEST_BYTES))
            for (method in listOf("GET", "PUT", "DELETE")) {
                assertFails { fixture.guard.prepare(iosDeletionTestRequest().apply { setHTTPMethod(method) }) }
            }
            for (size in listOf(0, Policy.MAX_REQUEST_BYTES + 1)) {
                assertFails { fixture.guard.prepare(iosDeletionTestRequest(size)) }
            }
            for (url in listOf(IOS_SESSION_TEST_URL, "$IOS_DELETION_URL?", "$IOS_DELETION_URL/other")) {
                assertFails { fixture.guard.prepare(iosDeletionTestRequest(url = url)) }
            }
            assertFails {
                fixture.guard.prepare(
                    iosDeletionTestRequest().apply { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1))) },
                )
            }
            assertFails { fixture.guard.prepare(iosDeletionTestRequest().apply { setValue("2", "Content-Length") }) }
            fixture.guard.prepare(iosDeletionTestRequest().apply { setValue("1", "Content-Length") })
        }

    @Test
    fun bodyAuthenticationRejectsBearerCookiesAmbiguousKeysAndUnexpectedHeaders() =
        withIosDeletionGuard { fixture ->
            for (name in listOf("Authorization", "Cookie", "Proxy-Authorization", "Content-Encoding", "If-Match")) {
                assertFails { fixture.guard.prepare(iosDeletionTestRequest().apply { setValue("synthetic", name) }) }
            }
            for (key in listOf(null, DELETION_TEST_KEY.uppercase(), "$DELETION_TEST_KEY,$DELETION_TEST_KEY")) {
                assertFails {
                    fixture.guard.prepare(iosDeletionTestRequest().apply { setValue(key, Policy.IDEMPOTENCY_HEADER) })
                }
            }
            for (value in listOf("synthetic", "Ktor-client", "ktor-client,ktor-client")) {
                assertFails { fixture.guard.prepare(iosDeletionTestRequest().apply { setValue(value, "User-Agent") }) }
            }
        }

    @Test
    fun actualPinnedSupplierHeaderMergeIsAcceptedWithoutRelaxingTheClosedSet() =
        withIosDeletionGuard { fixture ->
            val request =
                iosDeletionTestRequest().apply {
                    deletionTestEngineHeaders().forEach { (name, value) -> setValue(value, name) }
                }
            fixture.guard.prepare(request)
            val task = fixture.task(request)
            assertTrue(fixture.admit(task, mapOf("Content-Length" to "0"), IOS_DELETION_URL, 204))
            fixture.complete(task)
            assertNull(fixture.callbacks.errors.single())
        }
}
