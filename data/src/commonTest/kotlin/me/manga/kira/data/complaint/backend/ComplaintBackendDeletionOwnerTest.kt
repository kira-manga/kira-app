package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintBackendDeletionOwnerTest {
    @Test
    fun optionalDeletionIsInertAndAliasingAnyOtherBorrowedEngineIsRefused() =
        runTest {
            val fixture = DeletionOwnerFixture(this)
            try {
                val absent = fixture.create(null).reportSuccess()
                assertNull(absent.deletion)
                absent.close()
                fixture.engines.take(4).forEach { assertIs<AppResult.Failure>(fixture.create(it)) }
                assertNotNull(fixture.owner.deletion)
                assertEquals(0, fixture.keyCalls)
                assertTrue(fixture.engines.all { it.requestHistory.isEmpty() && it.coroutineContext.job.isActive })
                assertTrue(
                    fixture.storage.faults.trace
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ownerCloseFencesLateTerminalRetainsIntentAndNeverTakesOwnershipOfNativeEngines() =
        runTest {
            val fixture = DeletionOwnerFixture(this)
            val deletion = assertNotNull(fixture.owner.deletion)
            try {
                val prompt = deletion.requestDeletion().reportSuccess()
                val caller = async { deletion.confirmDeletion(prompt) }
                fixture.entered.await()
                fixture.owner.close()
                fixture.release.complete(Unit)
                assertFailsWith<CancellationException> { caller.await() }
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(deletingRecord()))
                assertTrue(Step.PENDING_CLEAR_BEFORE !in fixture.storage.faults.trace)
                assertTrue(fixture.engines.all { it.coroutineContext.job.isActive })
                assertIs<AppResult.Failure>(deletion.continueDeletion())
                assertEquals(1, fixture.keyCalls)
            } finally {
                fixture.close()
            }
        }
}

private class DeletionOwnerFixture(
    scope: TestScope,
) {
    val storage = InstallationCoordinatorFixture(Fixtures.record())
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var keyCalls = 0
        private set
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    val engines =
        List(5) { index ->
            historyMockEngine(scope) {
                when (index) {
                    SESSION -> respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                    DELETION ->
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            respond("", HttpStatusCode.NoContent, deletionHeaders())
                        }
                    else -> error("Deletion cannot enroll or use history/mutation routes")
                }
            }
        }
    val owner = create(engines[DELETION]).reportSuccess()

    fun create(deletion: HttpClientEngine?): AppResult<ComplaintBackendOwner> =
        ComplaintBackendOwner.create(
            endpoint,
            storage.credentials,
            storage.pending,
            EnrollmentMaterialGenerator(),
            engines[ENROLLMENT],
            engines[SESSION],
            engines[HISTORY],
            engines[MUTATION],
            deletionResources =
                deletion?.let { engine ->
                    ComplaintInstallationDeletionResources(engine) {
                        keyCalls += 1
                        Fixtures.KEY
                    }
                },
        )

    fun close() {
        release.complete(Unit)
        owner.close()
        engines.forEach { it.close() }
    }

    private companion object {
        const val ENROLLMENT = 0
        const val SESSION = 1
        const val HISTORY = 2
        const val MUTATION = 3
        const val DELETION = 4
    }
}
