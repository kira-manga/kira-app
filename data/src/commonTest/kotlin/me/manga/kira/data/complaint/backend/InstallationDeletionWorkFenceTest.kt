package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationDeletionWorkFenceTest {
    @Test
    fun finalCredentialReadSeparatelyFencesClosedHttpOrWorkEvenWhenApplyingCallerIsActive() =
        runTest {
            for (closeHttp in listOf(false, true)) {
                val fixture = DeletionFinalFenceFixture(this, closeHttp)
                try {
                    assertRefused(Block.STALE_BINDING, fixture.apply())
                    assertTrue(currentCoroutineContext().isActive)
                    fixture.assertPreserved()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun deletionLaneIsStillBusyDuringResponseCleanupAndReusableOnlyAfterCallerReturns() =
        runTest {
            val channel = DeletionCleanupProbe()
            val fixture =
                InstallationDeletionFixture(
                    this,
                    InstallationCoordinatorFixture(deletingRecord()),
                    deletionHandler = { respond(channel, HttpStatusCode.NoContent, deletionHeaders()) },
                )
            channel.works = fixture.works
            try {
                assertDeletionCompleted(fixture.repository.continueDeletion())
                assertTrue(channel.probed)
                assertNotNull(fixture.works.begin(channel.job)).finish()
            } finally {
                channel.job.cancel()
                fixture.close()
            }
        }
}

private class DeletionFinalFenceFixture(
    scope: TestScope,
    private val closeHttp: Boolean,
) {
    private var responded = false
    private val storage = InstallationCoordinatorFixture(deletingRecord())
    private val slot = Fixtures.slot(1).also { storage.pending.slots += it }
    private val fixture =
        InstallationDeletionFixture(
            scope,
            storage,
            deletionHandler = {
                responded = true
                respond("", HttpStatusCode.NoContent, deletionHeaders())
            },
        )
    private val job = Job()
    private val work = assertNotNull(fixture.works.begin(job))

    suspend fun apply(): Outcome<ComplaintInstallationDeletionOutcome> {
        val binding = fixture.coordinator.continueDeletion(work).success()
        storage.faults.onStep = { step ->
            if (responded && step == Step.CREDENTIAL_READ) {
                if (closeHttp) fixture.http.close() else fixture.works.close()
            }
        }
        return fixture.coordinator.dispatchDeletion(binding, fixture.http)
    }

    fun assertPreserved() {
        fixture.assertRetained(deletingRecord(), listOf(slot))
        assertTrue(storage.faults.mutations.isEmpty())
    }

    suspend fun close() {
        fixture.coordinator.finishDeletion(work)
        job.cancel()
        fixture.close()
    }
}

private class DeletionCleanupProbe(
    private val source: ByteReadChannel = ByteReadChannel(byteArrayOf()),
) : ByteReadChannel by source {
    val job = Job()
    var works: InstallationDeletionWorks? = null
    var probed = false
        private set

    override fun cancel(cause: Throwable?) {
        if (!probed) {
            probed = true
            assertNull(assertNotNull(works).begin(job))
        }
        source.cancel(cause)
    }
}
