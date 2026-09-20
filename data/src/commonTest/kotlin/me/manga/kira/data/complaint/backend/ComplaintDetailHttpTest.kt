package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintDetailHttpTest {
    @Test
    fun fixedAuthenticatedGetHasNoQueryBodyMutationKeyConditionalsOrLegacyIdentity() =
        runTest {
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    respond(historyItem().toString(), HttpStatusCode.OK, detailHeaders())
                })
            try {
                val result =
                    assertIs<AppResult.Success<ComplaintDetail>>(fixture.repository.loadComplaintDetail(historyId(100)))
                assertIs<ComplaintOwnerRow.Report>(assertIs<ComplaintDetail.Owned>(result.value).item)
                val request = fixture.historyRequests.single()
                assertEquals("$SESSION_BASE_URL/api/v1/complaints/${historyId(100)}", request.url.toString())
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.parameters.isEmpty())
                assertTrue(request.body.toByteArray().isEmpty())
                assertEquals(listOf("Bearer $SESSION_TOKEN"), request.headers.getAll(HttpHeaders.Authorization))
                assertEquals("application/json, application/problem+json", request.headers[HttpHeaders.Accept])
                assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                assertEquals("no-store, no-transform", request.headers[HttpHeaders.CacheControl])
                listOf(
                    "Cookie",
                    "Proxy-Authorization",
                    "Content-Type",
                    "X-Kira-Idempotency-Key",
                    "If-Match",
                    "If-None-Match",
                    "If-Modified-Since",
                    "If-Unmodified-Since",
                    "If-Range",
                ).forEach { assertNull(request.headers[it]) }
                assertEquals(1, fixture.sessionRequests.size)
                assertTrue(fixture.enrollment.requests.isEmpty())
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun onlyExactSingleResourceNotFoundBecomesUnavailableNeverDisabledOrSession404() =
        runTest {
            val exact = detailNotFoundProblem()
            val cases =
                listOf(
                    exact,
                    historyProblem(HttpStatusCode.NotFound),
                    historyInstallationNotFoundProblem(),
                    exact.replace("Not Found", "Unavailable"),
                    exact.replace("}]", "},{\"code\":\"INSTALLATION_NOT_FOUND\",\"message\":\"Not found.\"}]"),
                    exact.replace("\"status\":404", "\"status\":404,\"status\":404"),
                )
            for ((index, body) in cases.withIndex()) {
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        respond(body, HttpStatusCode.NotFound, sessionHeaders(HttpStatusCode.NotFound))
                    })
                try {
                    val result = fixture.repository.loadComplaintDetail(historyId(100))
                    when (index) {
                        0 -> assertSame(ComplaintDetail.Unavailable, assertIs<AppResult.Success<*>>(result).value)
                        cases.lastIndex ->
                            assertIs<AppError.Network.Serialization>(assertIs<AppResult.Failure>(result).error)
                        else ->
                            assertEquals(
                                404,
                                assertIs<AppError.Network.Http>(assertIs<AppResult.Failure>(result).error).statusCode,
                            )
                    }
                    assertEquals(1, fixture.historyRequests.size)
                    assertEquals(1, fixture.sessionRequests.size)
                    assertTrue(fixture.enrollment.requests.isEmpty())
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun noticeNeedsNeitherHttpNorBodyTagAndDoesNotAcquireContentAuthority() =
        runTest {
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = {
                    respond(detailNotice().toString(), HttpStatusCode.OK, sessionHeaders())
                })
            try {
                val result = assertIs<AppResult.Success<*>>(fixture.repository.loadComplaintDetail(DETAIL_NOTICE_ID)).value
                assertEquals(DETAIL_NOTICE_ID, assertIs<ComplaintDetail.Notice>(result).item.id)
                assertEquals(
                    "$SESSION_BASE_URL/api/v1/complaints/$DETAIL_NOTICE_ID",
                    fixture.historyRequests.single().url.toString(),
                )
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun conditionalNotModifiedRedirectPartialAndEmptyStatusesNeverBecomeSuccess() =
        runTest {
            val statuses =
                listOf(
                    HttpStatusCode.NoContent,
                    HttpStatusCode.PartialContent,
                    HttpStatusCode.Found,
                    HttpStatusCode.NotModified,
                )
            for (status in statuses) {
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = { respond("", status, sessionHeaders(status)) })
                try {
                    assertIs<AppError.Network.Serialization>(
                        assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error,
                    )
                    assertEquals(1, fixture.historyRequests.size)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun malformed401ChallengeOrProblemCannotTriggerRefresh() =
        runTest {
            val unauthorized = HttpStatusCode.Unauthorized
            val cases =
                listOf(
                    historyProblem(unauthorized) to sessionHeaders(unauthorized) { remove(HttpHeaders.WWWAuthenticate) },
                    historyProblem(unauthorized) to sessionHeaders(unauthorized) {
                        set(HttpHeaders.WWWAuthenticate, "Bearer realm=\"other\"")
                    },
                    detailNotFoundProblem() to sessionHeaders(unauthorized),
                    historyProblem(unauthorized) to sessionHeaders(unauthorized) {
                        append(HttpHeaders.ETag, detailActionTag())
                    },
                )
            for ((body, headers) in cases) {
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = { respond(body, unauthorized, headers) })
                try {
                    assertIs<AppError.Network.Serialization>(
                        assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error,
                    )
                    assertEquals(1, fixture.historyRequests.size)
                    assertEquals(1, fixture.sessionRequests.size)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun ordinaryErrorsNeverRetryEnrollClearEvidenceOrExposeServerText() =
        runTest {
            val statuses =
                listOf(
                    HttpStatusCode.Forbidden,
                    HttpStatusCode.Conflict,
                    HttpStatusCode.Gone,
                    HttpStatusCode.TooManyRequests,
                    HttpStatusCode.ServiceUnavailable,
                )
            for (status in statuses) {
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        respond(historyProblem(status), status, sessionHeaders(status))
                    })
                try {
                    val error = assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error
                    assertEquals(status.value, assertIs<AppError.Network.Http>(error).statusCode)
                    assertNull(error.cause)
                    assertEquals(1, fixture.historyRequests.size)
                    assertEquals(1, fixture.sessionRequests.size)
                    assertTrue(fixture.enrollment.requests.isEmpty())
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun transportFailureAndOwnedDeadlineAreSanitizedWithoutRetry() =
        runTest {
            for (timeout in listOf(false, true)) {
                val fixture =
                    ComplaintHistoryFixture(this, historyHandler = {
                        if (timeout) awaitCancellation() else error("Synthetic private wire diagnostics")
                    })
                try {
                    val error = assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100))).error
                    if (timeout) {
                        assertIs<AppError.Network.Timeout>(error)
                    } else {
                        assertIs<AppError.Network.NoConnectivity>(error)
                    }
                    assertNull(error.cause)
                    assertEquals(1, fixture.historyRequests.size)
                    assertEquals(1, fixture.sessionRequests.size)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }
}
