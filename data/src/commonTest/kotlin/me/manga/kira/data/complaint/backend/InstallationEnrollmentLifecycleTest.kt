package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationMaterialGenerationResult
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.platform.storage.InstallationCredentialState as State

class InstallationEnrollmentLifecycleTest {
    @Test
    fun verifiedBootstrapScopeAndCompleteReadbackPrecedeFirstSecretRequestOnBothPlatforms() =
        runTest {
            for (platform in listOf("ANDROID", "IOS")) {
                val storage = InstallationCoordinatorFixture()
                val fixture =
                    InstallationEnrollmentFixture(this, storage) { request ->
                        if (request.method == HttpMethod.Get) {
                            assertNull(storage.credentials.payloadRecord)
                            assertTrue(storage.faults.mutations.isEmpty())
                            respond(bootstrapResponse(Fixtures.OTHER_ID), HttpStatusCode.OK, sessionHeaders())
                        } else {
                            val record = assertNotNull(storage.credentials.payloadRecord)
                            assertEquals(Fixtures.OTHER_ID, record.material.dataScopeId)
                            assertEquals(platform, record.material.platform.name)
                            val trace = storage.faults.trace
                            assertTrue(trace.indexOf(Step.CREATE_STORED) < trace.lastIndexOf(Step.CREDENTIAL_READ))
                            assertTrue(trace.indexOf(Step.CREATE_STORED) < trace.lastIndexOf(Step.PENDING_READ))
                            respond(sessionResponse(record), HttpStatusCode.Created, enrollmentHeaders())
                        }
                    }
                val material = Fixtures.material(scope = Fixtures.OTHER_ID, platform = platform)
                fixture.generator.result = InstallationMaterialGenerationResult.Generated(material)
                try {
                    assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll())
                    assertEquals(listOf(Fixtures.OTHER_ID), fixture.generator.scopes)
                    assertEquals(listOf(Step.CREATE_BEFORE, Step.CREATE_STORED), storage.faults.mutations)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun everyCreationAndIndependentReadbackFailurePreventsSecretHttp() =
        runTest {
            val faults =
                listOf(
                    Step.CREATE_BEFORE to 1,
                    Step.CREATE_STORED to 1,
                    Step.CREDENTIAL_READ to 3,
                    Step.PENDING_READ to 4,
                )
            for ((step, occurrence) in faults) {
                val fixture = InstallationEnrollmentFixture(this)
                fixture.storage.faults.failAt(step, occurrence = occurrence)
                try {
                    val result = assertIs<InstallationEnrollmentResult.LocalFailure>(fixture.enroll())
                    assertStorageFailure(InstallationStoreFaults.ioFailure, result.outcome)
                    assertEquals(listOf(HttpMethod.Get), fixture.requests.map { it.method })
                    fixture.storage.credentials.payloadRecord
                        ?.let { assertTrue(it.sameAs(Fixtures.record())) }
                    assertTrue(
                        fixture.storage.pending.slots
                            .isEmpty(),
                    )
                    assertFalse(Step.CLEANUP_BEFORE in fixture.storage.faults.trace)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun fullRecordReadbackMismatchCannotPassByMatchingGenerationAlone() =
        runTest {
            val fixture = InstallationEnrollmentFixture(this)
            val changed = Fixtures.record(material = Fixtures.material(secret = "A".repeat(43)))
            val credentials = fixture.storage.credentials
            fixture.storage.faults.onStep = { if (it == Step.CREATE_STORED) credentials.install(changed) }
            try {
                val result = assertIs<InstallationEnrollmentResult.LocalFailure>(fixture.enroll())
                assertStorageFailure(
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH),
                    result.outcome,
                )
                assertEquals(listOf(HttpMethod.Get), fixture.requests.map { it.method })
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(changed))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun unavailableCorruptPendingDeletionAndConsentGatesDoNotReachEitherRoute() =
        runTest {
            val stores =
                listOf(
                    enrollmentStorage().apply { credentials.readFailure = InstallationStoreFaults.ioFailure },
                    enrollmentStorage().apply {
                        credentials.markerFailure =
                            InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
                    },
                    InstallationCoordinatorFixture().apply { credentials.keyPresent = true },
                    InstallationCoordinatorFixture().apply { pending.slots += Fixtures.slot(1) },
                    enrollmentStorage().apply { pending.slots += sessionPendingSlot(dispatched = true) },
                    enrollmentStorage(
                        Fixtures.record(generation = 2, state = State.DELETION_PENDING, key = Fixtures.KEY),
                    ),
                )
            for (storage in stores) {
                val fixture = InstallationEnrollmentFixture(this, storage)
                try {
                    assertIs<InstallationEnrollmentResult.LocalFailure>(fixture.enroll())
                    assertTrue(fixture.requests.isEmpty())
                    assertTrue(fixture.generator.scopes.isEmpty())
                    assertTrue(storage.faults.mutations.isEmpty())
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun outstandingRecoveryConsentBlocksEnrollmentWithoutHttp() =
        runTest {
            val fixture = InstallationEnrollmentFixture(this, enrollmentStorage())
            val permit =
                fixture.storage.coordinator
                    .admit()
                    .success()
            fixture.storage.coordinator
                .requestRecovery(RecoveryIntent.Reset(permit))
                .success()
            try {
                val result = assertIs<InstallationEnrollmentResult.LocalFailure>(fixture.enroll())
                assertRefused(Block.CONSENT_PENDING, result.outcome)
                assertTrue(fixture.requests.isEmpty())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun lostEnrollmentResponseAndRestartRetryOnlyTheExactDurableTuple() =
        runTest {
            val storage = InstallationCoordinatorFixture()
            val bodies = mutableListOf<String>()
            val fixture =
                InstallationEnrollmentFixture(this, storage) { request ->
                    if (request.method == HttpMethod.Get) {
                        respond(bootstrapResponse(Fixtures.OTHER_ID), HttpStatusCode.OK, sessionHeaders())
                    } else {
                        bodies += request.body.toByteArray().decodeToString()
                        if (bodies.size == 1) error("synthetic transport exception must not escape")
                        respond(
                            sessionResponse(assertNotNull(storage.credentials.payloadRecord)),
                            HttpStatusCode.OK,
                            enrollmentHeaders(HttpStatusCode.OK),
                        )
                    }
                }
            try {
                assertEquals(Failure.TRANSPORT, assertIs<InstallationEnrollmentResult.Failed>(fixture.enroll()).reason)
                val retained = assertNotNull(storage.credentials.payloadRecord)
                assertEquals(Fixtures.OTHER_ID, retained.material.dataScopeId)
                assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll(storage.restart()))
                assertEquals(2, bodies.size)
                assertEquals(bodies.first(), bodies.last())
                assertEquals(listOf(Fixtures.OTHER_ID), fixture.generator.scopes)
                assertEquals(1, storage.faults.trace.count { it == Step.CREATE_BEFORE })
                assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(retained))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun createIfAbsentCollisionUsesWinnerWithoutRebindingToBootstrapScope() =
        runTest {
            val fixture = InstallationEnrollmentFixture(this)
            val material = Fixtures.material(id = Fixtures.OTHER_ID, scope = Fixtures.OTHER_ID, platform = "IOS")
            val winner = Fixtures.record(material = material)
            val credentials = fixture.storage.credentials
            fixture.storage.faults.onStep = { if (it == Step.CREATE_BEFORE) credentials.install(winner) }
            try {
                assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll())
                assertEquals(listOf(Fixtures.SCOPE), fixture.generator.scopes)
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(winner))
                assertFalse(Step.CREATE_STORED in fixture.storage.faults.trace)
                assertFalse(Step.REPLACE_BEFORE in fixture.storage.faults.trace)
                val body =
                    fixture.requests
                        .last()
                        .body
                        .toByteArray()
                        .decodeToString()
                assertTrue(Fixtures.OTHER_ID in body)
                assertFalse(Fixtures.SCOPE in body)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun entropyUnsupportedAndWrongGeneratedScopeNeverPersistOrEnroll() =
        runTest {
            val failures =
                listOf(
                    InstallationMaterialGenerationResult.EntropyFailure,
                    InstallationMaterialGenerationResult.Unsupported,
                    InstallationMaterialGenerationResult.InvalidScope,
                    InstallationMaterialGenerationResult.Generated(Fixtures.material(scope = Fixtures.OTHER_ID)),
                )
            for (failure in failures) {
                val fixture = InstallationEnrollmentFixture(this)
                fixture.generator.result = failure
                try {
                    assertIs<InstallationEnrollmentResult.MaterialFailure>(fixture.enroll())
                    fixture.storage.assertAbsent()
                    assertTrue(
                        fixture.storage.faults.mutations
                            .isEmpty(),
                    )
                    assertEquals(listOf(HttpMethod.Get), fixture.requests.map { it.method })
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun onlyAlreadyAuthorizedStartupResetCleanupCanPrecedeNewEnrollment() =
        runTest {
            val reset = Fixtures.record(generation = 2, state = State.LOCAL_RESET_PENDING)
            val storage = enrollmentStorage(reset)
            storage.pending.slots += sessionPendingSlot(dispatched = true)
            val fresh = Fixtures.material(id = Fixtures.OTHER_ID)
            val fixture =
                InstallationEnrollmentFixture(this, storage) { request ->
                    if (request.method == HttpMethod.Get) {
                        storage.assertAbsent()
                        assertTrue(Step.MARKER_REMOVED in storage.faults.trace)
                        respond(bootstrapResponse(), HttpStatusCode.OK, sessionHeaders())
                    } else {
                        respond(
                            sessionResponse(assertNotNull(storage.credentials.payloadRecord)),
                            HttpStatusCode.Created,
                            enrollmentHeaders(),
                        )
                    }
                }
            fixture.generator.result = InstallationMaterialGenerationResult.Generated(fresh)
            try {
                assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll())
                val material = assertNotNull(storage.credentials.payloadRecord).material
                assertEquals(Fixtures.OTHER_ID, material.installationId)
                val removedAt = storage.faults.trace.indexOf(Step.MARKER_REMOVED)
                assertTrue(removedAt < storage.faults.trace.indexOf(Step.CREATE_BEFORE))
                assertTrue(storage.pending.slots.isEmpty())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun lateDifferentCredentialAfterResponseCannotPublishEnrollmentSuccess() =
        runTest {
            val storage = enrollmentStorage()
            val changed = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            val fixture =
                InstallationEnrollmentFixture(this, storage) {
                    storage.credentials.install(changed)
                    respond(sessionResponse(), HttpStatusCode.Created, enrollmentHeaders())
                }
            try {
                val result = assertIs<InstallationEnrollmentResult.LocalFailure>(fixture.enroll())
                assertRefused(Block.STALE_BINDING, result.outcome)
                assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(changed))
                assertTrue(storage.faults.mutations.isEmpty())
            } finally {
                fixture.close()
            }
        }
}
