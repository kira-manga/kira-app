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
class IosComplaintEnrollmentEngineTest {
    @Test
    fun invalidEnrollmentTargetsAndSessionFactoryRemainIsolated() {
        listOf(
            "http://example.invalid/api/v1/installations",
            "https://user@example.invalid/api/v1/installations",
            "$IOS_ENROLLMENT_TEST_URL?extra=1",
            "$IOS_ENROLLMENT_TEST_URL#extra",
            "$IOS_ENROLLMENT_TEST_URL/",
            IOS_BOOTSTRAP_TEST_URL,
            IOS_SESSION_TEST_URL,
        ).forEach(::assertEnrollmentTargetRejected)
        listOf(IOS_ENROLLMENT_TEST_URL, IOS_BOOTSTRAP_TEST_URL).forEach { value ->
            val owner = createIosComplaintSessionEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
        val prefixed = "https://example.invalid:9443/prefix/api/v1/installations"
        val checked = assertNotNull(iosComplaintEnrollmentTarget(Url(prefixed)))
        assertTrue(checked.matchesEnrollment(prefixed))
        assertTrue(checked.matchesBootstrap("$prefixed/bootstrap"))
        assertFalse(checked.matchesEnrollment(IOS_ENROLLMENT_TEST_URL))
    }

    @Test
    fun onlyGetBootstrapAndPostEnrollmentPassNativePreparation() =
        withIosEnrollmentGuard { fixture ->
            listOf(true, false).forEach { bootstrap ->
                val request = iosEnrollmentTestRequest(bootstrap)
                fixture.guard.prepare(request)
                assertFalse(request.HTTPShouldHandleCookies)
                assertEquals(NSURLRequestReloadIgnoringLocalCacheData, request.cachePolicy)
            }
            listOf(
                "https://elsewhere.invalid/api/v1/installations",
                "https://example.invalid:9443/api/v1/installations",
                "https://example.invalid/prefix/api/v1/installations",
                "$IOS_ENROLLMENT_TEST_URL?extra=1",
                "$IOS_ENROLLMENT_TEST_URL/extra",
                IOS_SESSION_TEST_URL,
                IOS_BOOTSTRAP_TEST_URL,
            ).forEach { url -> assertFails { fixture.guard.prepare(iosSessionTestRequest(url)) } }
            listOf("GET", "PUT", "HEAD").forEach { method ->
                assertFails {
                    fixture.guard.prepare(iosEnrollmentTestRequest().apply { setHTTPMethod(method) })
                }
            }
            assertFails {
                fixture.guard.prepare(iosEnrollmentTestRequest(bootstrap = true).apply { setHTTPMethod("POST") })
            }
        }

    @Test
    fun bootstrapRefusesEmptyOrNonemptyBodiesStreamsAndBodyHeaders() =
        withIosEnrollmentGuard { fixture ->
            listOf(0, 1).forEach { size ->
                assertFails {
                    fixture.guard.prepare(
                        iosEnrollmentTestRequest(bootstrap = true).apply { setHTTPBody(iosSessionTestData(size)) },
                    )
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosEnrollmentTestRequest(bootstrap = true).apply {
                        setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1)))
                    },
                )
            }
            listOf("Content-Type", "Content-Length", "Transfer-Encoding", "Content-Encoding").forEach { header ->
                assertFails {
                    fixture.guard.prepare(
                        iosEnrollmentTestRequest(bootstrap = true).apply { setValue("0", header) },
                    )
                }
            }
        }

    @Test
    fun enrollmentRequiresNonemptyBoundedNSDataRatherThanAStream() =
        withIosEnrollmentGuard { fixture ->
            fixture.guard.prepare(
                iosEnrollmentTestRequest().apply { setHTTPBody(iosSessionTestData(MAX_REQUEST_BYTES)) },
            )
            assertFails { fixture.guard.prepare(iosEnrollmentTestRequest().apply { setHTTPBody(null) }) }
            listOf(0, MAX_REQUEST_BYTES + 1).forEach { size ->
                assertFails {
                    fixture.guard.prepare(iosEnrollmentTestRequest().apply { setHTTPBody(iosSessionTestData(size)) })
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosEnrollmentTestRequest().apply {
                        setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1)))
                    },
                )
            }
        }

    @Test
    fun bothRoutesRequireIdentityEncodingAndRejectCredentialHeaders() =
        withIosEnrollmentGuard { fixture ->
            listOf(true, false).forEach { bootstrap ->
                listOf(null, "gzip", "identity, identity").forEach { encoding ->
                    assertFails {
                        fixture.guard.prepare(
                            iosEnrollmentTestRequest(bootstrap).apply { setValue(encoding, "Accept-Encoding") },
                        )
                    }
                }
                listOf("Cookie", "Authorization", "Proxy-Authorization").forEach { header ->
                    assertFails {
                        fixture.guard.prepare(
                            iosEnrollmentTestRequest(bootstrap).apply { setValue("synthetic-only", header) },
                        )
                    }
                }
            }
        }

    @Test
    fun borrowedEnrollmentClientAndOwnerCloseDoNotCloseIndependentSessionOwner() {
        val owner = assertNotNull(createIosComplaintEnrollmentEngineOwner(Url(IOS_ENROLLMENT_TEST_URL)))
        try {
            withIosSessionEngineOwner { sessionOwner ->
                val client = HttpClient(owner.engine)
                try {
                    client.close()
                    assertTrue(assertNotNull(owner.engine.coroutineContext[Job]).isActive)
                    owner.close()
                    owner.close()
                    assertFalse(assertNotNull(owner.engine.coroutineContext[Job]).isActive)
                    assertTrue(assertNotNull(sessionOwner.engine.coroutineContext[Job]).isActive)
                } finally {
                    client.close()
                }
            }
        } finally {
            owner.close()
        }
    }

    private fun assertEnrollmentTargetRejected(value: String) {
        val owner = createIosComplaintEnrollmentEngineOwner(Url(value))
        try {
            assertNull(owner)
        } finally {
            owner?.close()
        }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 4 * 1_024
    }
}
