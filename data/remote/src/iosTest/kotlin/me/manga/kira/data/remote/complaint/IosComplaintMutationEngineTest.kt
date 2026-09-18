package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.HTTPMethod
import platform.Foundation.NSInputStream
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class IosComplaintMutationEngineTest {
    @Test
    fun factoryRejectsStatusOrHistoryQueriesButDerivesTheExactDeploymentPrefix() {
        listOf(
            IOS_MUTATION_STATUS_URL,
            "$IOS_MUTATION_CREATE_URL?limit=50",
            "$IOS_MUTATION_CREATE_URL/",
            "https://example.invalid/bad/../api/v1/complaints",
            "https://user@example.invalid/api/v1/complaints",
        ).forEach { value ->
            val owner = createIosComplaintMutationEngineOwner(Url(value))
            try {
                assertNull(owner)
            } finally {
                owner?.close()
            }
        }
        val base = "https://example.invalid:9443/base_1/v2"
        val target = assertNotNull(iosComplaintMutationTarget(Url(base + Policy.CREATE_PATH)))
        assertEquals(ComplaintMutationRoute.STATUS, target.route(base + Policy.STATUS_PATH))
        assertNull(target.route(IOS_MUTATION_STATUS_URL))
    }

    @Test
    fun onlyClosedPostRoutesWithTheCorrectKeyPresencePassNativePreparation() =
        withIosMutationGuard { fixture ->
            listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)
                .forEach { route ->
                    val request = iosMutationTestRequest(route)
                    assertEquals("POST", request.HTTPMethod)
                    fixture.guard.prepare(request)
                }
            listOf("GET", "PUT", "DELETE").forEach { method ->
                assertFails { fixture.guard.prepare(iosMutationTestRequest().apply { setHTTPMethod(method) }) }
            }
            listOf(
                "$IOS_MUTATION_CREATE_URL?limit=50",
                "$IOS_MUTATION_CREATE_URL/other",
                IOS_MUTATION_CREATE_URL.replace("example.invalid", "elsewhere.invalid"),
                IOS_MUTATION_STATUS_URL,
            ).forEach { url -> assertFails { fixture.guard.prepare(iosMutationTestRequest(url = url)) } }
            listOf(null, "$MUTATION_TEST_KEY, $MUTATION_TEST_KEY", MUTATION_TEST_KEY.uppercase()).forEach { key ->
                assertFails {
                    fixture.guard.prepare(iosMutationTestRequest().apply { setValue(key, Policy.IDEMPOTENCY_HEADER) })
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosMutationTestRequest(ComplaintMutationRoute.STATUS).apply {
                        setValue(MUTATION_TEST_KEY, Policy.IDEMPOTENCY_HEADER)
                    },
                )
            }
            listOf("If-Match", "Cookie", "Proxy-Authorization", "Content-Encoding", "Transfer-Encoding")
                .forEach { name ->
                    assertFails {
                        fixture.guard.prepare(iosMutationTestRequest().apply { setValue("synthetic", name) })
                    }
                }
        }

    @Test
    fun requestLimitUsesActualNSDataAndRefusesUnknownStreamsOrFalseLength() =
        withIosMutationGuard { fixture ->
            ComplaintMutationRoute.entries.forEach { route ->
                fixture.guard.prepare(iosMutationTestRequest(route, Policy.MAX_REQUEST_BYTES))
                listOf(0, Policy.MAX_REQUEST_BYTES + 1).forEach { size ->
                    assertFails { fixture.guard.prepare(iosMutationTestRequest(route, size)) }
                }
            }
            assertFails {
                fixture.guard.prepare(
                    iosMutationTestRequest().apply { setHTTPBodyStream(NSInputStream(data = iosSessionTestData(1))) },
                )
            }
            assertFails { fixture.guard.prepare(iosMutationTestRequest().apply { setValue("2", "Content-Length") }) }
            fixture.guard.prepare(iosMutationTestRequest().apply { setValue("1", "Content-Length") })
        }

    @Test
    fun supplierMergedHeadersPassPreparationAndOriginalRequestButCustomUserAgentsFail() {
        ComplaintMutationRoute.entries.forEach { route ->
            withIosMutationGuard { fixture ->
                val request =
                    iosMutationTestRequest(route).apply {
                        mutationTestEngineHeaders(route).forEach { (name, value) -> setValue(value, name) }
                    }
                fixture.guard.prepare(request)
                listOf("synthetic", "Ktor-client", "ktor-client,ktor-client").forEach { value ->
                    assertFails {
                        fixture.guard.prepare(iosMutationTestRequest(route).apply { setValue(value, "user-agent") })
                    }
                }
                val task = fixture.task(request)
                val headers = mapOf("Content-Type" to "application/json", "Content-Length" to "1")
                assertTrue(fixture.admit(task, headers, iosMutationTestUrl(route)))
                fixture.receive(task, 1)
                fixture.complete(task)
                assertEquals(1uL, fixture.callbacks.forwardedBytes)
                assertNull(fixture.callbacks.errors.single())
            }
        }
    }
}
