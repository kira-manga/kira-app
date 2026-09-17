package me.manga.kira.data.remote.complaint

import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.Job
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSInputStream
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintHistoryEngineTest {
    @Test
    fun historyFactoryCannotSelectInstallationOrAcceptAConfiguredQuery() {
        listOf(
            "http://example.invalid/api/v1/complaints",
            "https://user@example.invalid/api/v1/complaints",
            IOS_HISTORY_FIRST_PAGE,
            "$IOS_HISTORY_TEST_URL/other",
            "$IOS_HISTORY_TEST_URL/",
            IOS_SESSION_TEST_URL,
            IOS_ENROLLMENT_TEST_URL,
        ).forEach { assertNoOwner(createIosComplaintHistoryEngineOwner(Url(it))) }
        assertNoOwner(createIosComplaintSessionEngineOwner(Url(IOS_HISTORY_TEST_URL)))
        assertNoOwner(createIosComplaintEnrollmentEngineOwner(Url(IOS_HISTORY_TEST_URL)))
        val base = "https://example.invalid:9443/base_1/v2/api/v1/complaints"
        val target = assertNotNull(iosComplaintHistoryTarget(Url(base)))
        assertTrue(target.matches("$base?limit=50&cursor=v1.a.b"))
        assertFalse(target.matches(IOS_HISTORY_FIRST_PAGE))
    }

    @Test
    fun onlyExactGetHistoryPagesPassNativePreparation() =
        withIosHistoryGuard { fixture ->
            val request = iosHistoryTestRequest()
            fixture.guard.prepare(request)
            assertFalse(request.HTTPShouldHandleCookies)
            assertEquals(NSURLRequestReloadIgnoringLocalCacheData, request.cachePolicy)
            fixture.guard.prepare(iosHistoryTestRequest("$IOS_HISTORY_FIRST_PAGE&cursor=v1.a.b"))
            listOf(
                IOS_HISTORY_TEST_URL,
                IOS_SESSION_TEST_URL,
                "$IOS_HISTORY_TEST_URL/other?limit=50",
                IOS_HISTORY_FIRST_PAGE.replace("example.invalid", "elsewhere.invalid"),
                IOS_HISTORY_FIRST_PAGE.replace("example.invalid", "example.invalid:9443"),
                "$IOS_HISTORY_FIRST_PAGE&limit=50",
                "$IOS_HISTORY_FIRST_PAGE&extra=1",
                "$IOS_HISTORY_FIRST_PAGE&cursor=",
                "$IOS_HISTORY_FIRST_PAGE&cursor=v1.a.b&cursor=v1.a.c",
                "$IOS_HISTORY_TEST_URL?%6cimit=50",
                "$IOS_HISTORY_FIRST_PAGE&cursor=v1.%61.b",
            ).forEach { value -> assertFails { fixture.guard.prepare(iosHistoryTestRequest(value)) } }
            listOf("POST", "HEAD", "PUT").forEach { method ->
                assertFails { fixture.guard.prepare(iosHistoryTestRequest().apply { setHTTPMethod(method) }) }
            }
        }

    @Test
    fun historyRejectsEveryBodyRepresentationAndFramingHeader() =
        withIosHistoryGuard { fixture ->
            listOf(0, 1).forEach { size ->
                assertFails {
                    fixture.guard.prepare(iosHistoryTestRequest().apply { setHTTPBody(iosSessionTestData(size)) })
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosHistoryTestRequest().apply { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1))) },
                )
            }
            listOf("Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding").forEach { name ->
                assertFails { fixture.guard.prepare(iosHistoryTestRequest().apply { setValue("0", name) }) }
            }
        }

    @Test
    fun authorizationIsRequiredButCookiesProxyCredentialsAndAmbiguousHeadersAreRefused() =
        withIosHistoryGuard { fixture ->
            listOf("Cookie", "Cookie2", "Proxy-Authorization").forEach { name ->
                assertFails { fixture.guard.prepare(iosHistoryTestRequest().apply { setValue("synthetic", name) }) }
            }
            listOf(null, "Basic synthetic", "$IOS_HISTORY_TEST_AUTHORIZATION, $IOS_HISTORY_TEST_AUTHORIZATION")
                .forEach { value ->
                    assertFails {
                        fixture.guard.prepare(iosHistoryTestRequest().apply { setValue(value, "Authorization") })
                    }
                }
            listOf(null, "gzip", "identity, identity").forEach { value ->
                assertFails {
                    fixture.guard.prepare(iosHistoryTestRequest().apply { setValue(value, "Accept-Encoding") })
                }
            }
        }

    @Test
    fun historyClientBorrowsItsOwnEngineWithoutClosingTheIndependentInstallationOwner() {
        val owner = assertNotNull(createIosComplaintHistoryEngineOwner(Url(IOS_HISTORY_TEST_URL)))
        try {
            withIosSessionEngineOwner { installation ->
                val client = HttpClient(owner.engine)
                try {
                    client.close()
                    assertTrue(assertNotNull(owner.engine.coroutineContext[Job]).isActive)
                    owner.close()
                    owner.close()
                    assertFalse(assertNotNull(owner.engine.coroutineContext[Job]).isActive)
                    assertTrue(assertNotNull(installation.engine.coroutineContext[Job]).isActive)
                } finally {
                    client.close()
                }
            }
        } finally {
            owner.close()
        }
    }

    private fun assertNoOwner(owner: ComplaintSessionEngineOwner?) {
        try {
            assertNull(owner)
        } finally {
            owner?.close()
        }
    }
}
