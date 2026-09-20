package me.manga.kira.sources.runtime

import androidx.room.useWriterConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.sources.config.SourceSelectionBootstrap
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** Real Room/provider admission only; the existing empty-only setup is not native/graph qualification. */
class RoomDownloadCatalogAdmissionTest {
    @Test
    fun preparationDoesNotAuthorizeCaptureAfterProcessOrDurableMismatch() = runTest {
        withAdmission { fixture, admission, operations ->
            var captures = 0
            assertFailsWith<DownloadCatalogNotReady> { admission.withAdmittedOperation { captures++ } }
            assertIs<AppResult.Success<*>>(admission.prepareLocal())
            val selected = assertNotNull(fixture.store.readSelection().selection)

            fixture.store.invalidateSelection()
            assertEquals(selected, fixture.store.readSelection().selection)
            assertFailsWith<DownloadCatalogNotReady> { admission.withAdmittedOperation { captures++ } }

            // A new explicit caller may prepare again; no cached successful outcome grants admission.
            assertIs<AppResult.Success<*>>(admission.prepareLocal())
            // Deliberate external SQL drift, not a production mutation/admission bypass.
            fixture.execute("UPDATE effective_source_selection SET payload = '{}'")
            assertFailsWith<DownloadCatalogNotReady> { admission.withAdmittedOperation { captures++ } }
            assertEquals(0, captures)
            operations.withExclusive {} // Every refused proof has actually left its operation/writer.
        }
    }

    @Test
    fun matchingProofExposesTheOwnedHandleAndKeepsExclusiveOutThroughCallerLifetime() = runTest {
        withAdmission { fixture, admission, operations ->
            assertIs<AppResult.Success<*>>(admission.prepareLocal())
            val child = admission.withAdmittedOperation { operation ->
                assertSame(operation, currentCoroutineContext()[DownloadOperationExclusion.Operation])
                fixture.db.useWriterConnection { assertFalse(it.inTransaction(), "Caller I/O must not inherit the proof writer") }
                // Launch on this test's independent scope, not the admitted operation's context.
                async { assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} } }.await()
                fixture.store.invalidateSelection()
                async { assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} } }.await()
                operation.retain() // Actual asynchronous consumers must retain before handoff too.
            }
            try {
                assertFailsWith<DownloadOperationBusy> { operations.withExclusive {} }
                assertFailsWith<DownloadCatalogNotReady> { admission.withAdmittedOperation { error("must not capture") } }
            } finally {
                child.release()
            }
            operations.withExclusive {}
        }
    }

    @Test
    fun callerFailureIsNotRelabeledAsPreCaptureNotReady() = runTest {
        withAdmission { _, admission, operations ->
            assertIs<AppResult.Success<*>>(admission.prepareLocal())
            val original = SourceSelectionUnavailable("fixture caller failure after admission")
            val thrown = assertFailsWith<SourceSelectionUnavailable> {
                admission.withAdmittedOperation<Unit> { throw original }
            }
            // JVM coroutine stack recovery may copy the exception at withContext's boundary,
            // retaining the original as its cause. Require that provenance, not just equal text.
            assertEquals(original.message, thrown.message)
            assertSame(original, thrown.cause ?: thrown)
            operations.withExclusive {}
        }
    }
}

private suspend fun TestScope.withAdmission(
    block: suspend (EffectiveSourceSelectionRoomFixture, RoomDownloadCatalogAdmission, DownloadOperationExclusion) -> Unit,
) {
    EffectiveSourceSelectionRoomFixture().use { fixture ->
        val bootstrap = SourceSelectionBootstrap(fixture.manager(), this)
        val operations = DownloadOperationExclusion()
        val admission = RoomDownloadCatalogAdmission(bootstrap, operations, RoomMangaWriteTransaction(fixture.db), fixture.provider)
        try {
            block(fixture, admission, operations)
        } finally {
            bootstrap.close()
        }
    }
}
