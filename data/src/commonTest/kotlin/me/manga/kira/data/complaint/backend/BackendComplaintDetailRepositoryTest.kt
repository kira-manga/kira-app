package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class BackendComplaintDetailRepositoryTest {
    @Test
    fun missingIdentityDoesNotBootstrapEnrollGenerateOrPersistAnything() =
        runTest {
            val fixture = ComplaintHistoryFixture(this, storage = InstallationCoordinatorFixture())
            try {
                assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100)))
                fixture.storage.assertAbsent()
                assertNoDetailTraffic(fixture)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun existingCleanupCorruptOrLockedEvidenceIsNotResumedOrTreatedAsMissing() =
        runTest {
            for (state in listOf("cleanup", "corrupt", "locked")) {
                val storage = blockedDetailStorage(state)
                val fixture = ComplaintHistoryFixture(this, storage = storage)
                try {
                    assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100)))
                    assertNoDetailTraffic(fixture)
                    if (state == "cleanup") {
                        assertEquals(
                            InstallationCredentialState.LOCAL_RESET_PENDING,
                            storage.credentials.payloadRecord?.state,
                        )
                        assertEquals(CredentialCleanupReason.USER_RESET_CONFIRMED, storage.credentials.marker?.reason)
                    }
                    if (state == "corrupt") assertEquals(1, storage.pending.slots.size)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun evenExactNeverClaimedSession404CannotEnrollForDetail() =
        runTest {
            for (body in listOf(historyInstallationNotFoundProblem(), historyProblem(HttpStatusCode.NotFound))) {
                val fixture =
                    ComplaintHistoryFixture(this, sessionHandler = {
                        respond(body, HttpStatusCode.NotFound, sessionHeaders(HttpStatusCode.NotFound))
                    })
                try {
                    assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100)))
                    assertEquals(1, fixture.sessionRequests.size)
                    assertTrue(fixture.historyRequests.isEmpty())
                    assertTrue(fixture.enrollment.requests.isEmpty())
                    assertTrue(fixture.enrollment.generator.scopes.isEmpty())
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun retainedPreparedAndMayHaveDispatchedReportAndReplyMetadataAreBytePreserved() =
        runTest {
            for (reply in listOf(false, true)) {
                for (dispatched in listOf(false, true)) {
                    for (unavailable in listOf(false, true)) assertPendingRead(reply, dispatched, unavailable)
                }
            }
        }

    @Test
    fun oneMatching401RefreshesSameIdAndBindingButSecond401Stops() =
        runTest {
            for (secondUnauthorized in listOf(false, true)) assertBoundedRefresh(secondUnauthorized)
        }

    @Test
    fun changedPendingInventoryDuringRefreshCannotAuthorizeAnotherDispatch() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val slot = reportSlot(mobileReplyRequest())
            var sessionCalls = 0
            val fixture =
                ComplaintHistoryFixture(this, storage = storage, sessionHandler = {
                    if (++sessionCalls == 2) storage.pending.slots += slot
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }, historyHandler = {
                    respond(
                        historyProblem(HttpStatusCode.Unauthorized),
                        HttpStatusCode.Unauthorized,
                        sessionHeaders(HttpStatusCode.Unauthorized),
                    )
                })
            try {
                assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100)))
                assertEquals(2, fixture.sessionRequests.size)
                assertEquals(1, fixture.historyRequests.size)
                fixture.assertPreserved(slots = listOf(slot))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun unavailableParentLookupDoesNotRewriteAlreadyReadChildOrInferParentMetadata() =
        runTest {
            val child = JsonObject(historyItem(kind = "REPLY") + ("replyToId" to JsonPrimitive(DETAIL_NOTICE_ID)))
            val fixture =
                ComplaintHistoryFixture(this, historyHandler = { request ->
                    if (request.url.encodedPath.endsWith(DETAIL_NOTICE_ID)) {
                        respond(detailNotFoundProblem(), HttpStatusCode.NotFound, sessionHeaders(HttpStatusCode.NotFound))
                    } else {
                        respond(child.toString(), HttpStatusCode.OK, detailHeaders())
                    }
                })
            try {
                val read = assertIs<AppResult.Success<*>>(fixture.repository.loadComplaintDetail(historyId(100))).value
                val row = assertIs<ComplaintOwnerRow.Reply>(assertIs<ComplaintDetail.Owned>(read).item)
                val parent = assertIs<AppResult.Success<*>>(fixture.repository.loadComplaintDetail(row.replyToId)).value
                assertSame(ComplaintDetail.Unavailable, parent)
                assertEquals(DETAIL_NOTICE_ID, row.replyToId)
                assertEquals("Synthetic subject", row.subject)
                assertEquals(detailActionTag(), row.fields.actionTag)
                assertEquals(2, fixture.historyRequests.size)
                assertEquals(1, fixture.sessionRequests.size)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    private suspend fun TestScope.assertPendingRead(
        reply: Boolean,
        dispatched: Boolean,
        unavailable: Boolean,
    ) {
        val slot = reportSlot(if (reply) mobileReplyRequest() else mutationReport(), dispatched)
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = {
                if (unavailable) {
                    respond(detailNotFoundProblem(), HttpStatusCode.NotFound, sessionHeaders(HttpStatusCode.NotFound))
                } else {
                    respond(historyItem().toString(), HttpStatusCode.OK, detailHeaders())
                }
            })
        fixture.storage.pending.slots += slot
        try {
            assertIs<AppResult.Success<*>>(fixture.repository.loadComplaintDetail(historyId(100)))
            assertEquals(1, fixture.historyRequests.size)
            assertTrue(fixture.enrollment.requests.isEmpty())
            assertTrue(fixture.enrollment.generator.scopes.isEmpty())
            fixture.assertPreserved(slots = listOf(slot))
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.assertBoundedRefresh(secondUnauthorized: Boolean) {
        var calls = 0
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = {
                if (++calls == 1 || secondUnauthorized) {
                    respond(
                        historyProblem(HttpStatusCode.Unauthorized),
                        HttpStatusCode.Unauthorized,
                        sessionHeaders(HttpStatusCode.Unauthorized),
                    )
                } else {
                    respond(historyItem().toString(), HttpStatusCode.OK, detailHeaders())
                }
            })
        try {
            val result = fixture.repository.loadComplaintDetail(historyId(100))
            if (secondUnauthorized) {
                assertEquals(401, assertIs<AppError.Network.Http>(assertIs<AppResult.Failure>(result).error).statusCode)
            } else {
                assertIs<ComplaintDetail.Owned>(assertIs<AppResult.Success<*>>(result).value)
            }
            assertEquals(2, fixture.historyRequests.size)
            assertEquals(2, fixture.sessionRequests.size)
            assertEquals(fixture.historyRequests[0].url, fixture.historyRequests[1].url)
            assertTrue(fixture.enrollment.requests.isEmpty())
            fixture.assertPreserved()
        } finally {
            fixture.close()
        }
    }
}

private fun assertNoDetailTraffic(fixture: ComplaintHistoryFixture) {
    assertTrue(fixture.historyRequests.isEmpty())
    assertTrue(fixture.sessionRequests.isEmpty())
    assertTrue(fixture.enrollment.requests.isEmpty())
    assertTrue(fixture.enrollment.generator.scopes.isEmpty())
    assertTrue(fixture.storage.faults.mutations.isEmpty())
}

private fun blockedDetailStorage(state: String): InstallationCoordinatorFixture =
    InstallationCoordinatorFixture(Fixtures.record()).apply {
        when (state) {
            "cleanup" -> {
                credentials.install(
                    Fixtures.record(generation = 2, state = InstallationCredentialState.LOCAL_RESET_PENDING),
                )
                credentials.marker = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
            }
            "corrupt" -> pending.slots += Fixtures.slot(1)
            else ->
                credentials.readFailure =
                    InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED)
        }
    }
