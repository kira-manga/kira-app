package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.PendingComplaintHold as Hold
import me.manga.kira.data.complaint.backend.PendingComplaintOperation as Operation
import me.manga.kira.data.complaint.backend.PendingComplaintTransitions as Transitions

/** Pure metadata examples, never authenticated sessions, recomputed request digests or storage/read-back evidence. */
class PendingComplaintRecordTest {
    @Test
    fun operationTuplesKeepCanonicalRolesAndExactPreconditions() {
        val report = pendingAction()
        val reply = pendingAction(Operation.CREATE_REPLY, parent = SEEDED_ID)
        val edit = pendingAction(Operation.EDIT_CONTENT, SEEDED_ID, version = 1)
        val delete = pendingAction(Operation.DELETE_OWNED, SEEDED_ID, version = Long.MAX_VALUE)
        assertEquals(listOf(Fixtures.OTHER_ID), report.orderedTargetIds())
        assertEquals(listOf(SEEDED_ID, Fixtures.OTHER_ID), reply.orderedTargetIds())
        assertEquals(listOf(SEEDED_ID), edit.orderedTargetIds())
        assertEquals(listOf(SEEDED_ID), delete.orderedTargetIds())
        assertNull(report.canonicalPrecondition())
        assertNull(reply.canonicalPrecondition())
        assertEquals("\"complaint-$SEEDED_ID-v1\"", edit.canonicalPrecondition())
        assertEquals("\"complaint-$SEEDED_ID-v${Long.MAX_VALUE}\"", delete.canonicalPrecondition())
        assertEquals(
            listOf("CREATE_REPORT", "CREATE_REPLY", "EDIT_CONTENT", "DELETE_OWNED"),
            Operation.entries.map { it.name },
        )
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPORT, SEEDED_ID, null, null))
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPORT, Fixtures.OTHER_ID, SEEDED_ID, null))
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPORT, Fixtures.OTHER_ID, null, 1))
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPLY, SEEDED_ID, Fixtures.ID, null))
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPLY, Fixtures.OTHER_ID, null, null))
        assertNull(PendingComplaintAction.checked(Operation.CREATE_REPLY, Fixtures.OTHER_ID, SEEDED_ID, 1))
        assertNull(PendingComplaintAction.checked(Operation.EDIT_CONTENT, SEEDED_ID, Fixtures.ID, 1))
        assertNull(PendingComplaintAction.checked(Operation.EDIT_CONTENT, SEEDED_ID, null, null))
        assertNull(PendingComplaintAction.checked(Operation.EDIT_CONTENT, SEEDED_ID, null, 0))
        assertNull(PendingComplaintAction.checked(Operation.DELETE_OWNED, SEEDED_ID, null, -1))
        listOf(
            Fixtures.OTHER_ID.uppercase(),
            Fixtures.OTHER_ID.replace("-", ""),
            " ${Fixtures.OTHER_ID}",
        ).forEach { alias ->
            assertNull(PendingComplaintAction.checked(Operation.CREATE_REPORT, alias, null, null))
            assertNull(PendingComplaintAction.checked(Operation.CREATE_REPLY, Fixtures.ID, alias, null))
            assertNull(PendingComplaintAction.checked(Operation.DELETE_OWNED, alias, null, 1))
        }
    }

    @Test
    fun bindingsAndFingerprintsAreSyntaxOnlyAndRedacted() {
        val record = pendingRecord()
        val binding = record.binding
        assertTrue(binding.matches(Fixtures.record()))
        assertTrue(
            binding.matches(
                Fixtures.record(material = Fixtures.material(secret = "B".repeat(42) + "A", platform = "IOS")),
            ),
        )
        foreignCredentials().forEach { assertFalse(binding.matches(it)) }
        assertNull(PendingComplaintBinding.checked(Fixtures.OTHER_ID.uppercase(), 1, 1, Fixtures.SCOPE))
        assertNull(PendingComplaintBinding.checked(Fixtures.ID, 0, 1, Fixtures.SCOPE))
        assertNull(PendingComplaintBinding.checked(Fixtures.ID, 1, 0, Fixtures.SCOPE))
        assertNull(PendingComplaintBinding.checked(Fixtures.ID, 1, 1, SEEDED_ID))
        listOf("A".repeat(43), "_".repeat(42) + "8", "-".repeat(42) + "w").forEach { text ->
            val fingerprint = assertNotNull(PendingComplaintFingerprint.checked(1, text))
            assertEquals(1, fingerprint.version)
            assertEquals(text, fingerprint.encoded)
        }
        listOf("", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "B", "A".repeat(42) + "=", "+".repeat(43)).forEach {
            assertNull(PendingComplaintFingerprint.checked(1, it))
        }
        assertNull(PendingComplaintFingerprint.checked(0, testFingerprint))
        assertNull(PendingComplaintFingerprint.checked(2, testFingerprint))
        assertNull(PendingComplaintRequest.checked(record.request.action, SEEDED_ID, record.request.fingerprint))
        assertNull(
            PendingComplaintRequest.checked(
                record.request.action,
                Fixtures.OTHER_ID.uppercase(),
                record.request.fingerprint,
            ),
        )
        val diagnostics =
            listOf(binding, record.request.action, record.request.fingerprint, record.request, record.times, record)
        diagnostics.forEach { assertTrue(it.toString().endsWith("(redacted)")) }
        val serialized = encodePending(record).bytes().decodeToString()
        assertFalse(Fixtures.secret in serialized)
        listOf("subject", "body", "secret", "headers", "token", "platform").forEach {
            assertFalse("\"$it\":" in serialized)
        }
    }

    // Keep canonical roundtrips and hostile byte variants in one boundary group, not split only to satisfy line count.
    @Suppress("LongMethod")
    @Test
    fun codecAcceptsOnlyExactCanonicalBoundedBytes() {
        val golden = pendingGolden()
        val slot = encodePending(pendingRecord())
        assertEquals(golden, slot.bytes().decodeToString())
        listOf(
            pendingAction(),
            pendingAction(Operation.CREATE_REPLY, parent = SEEDED_ID),
            pendingAction(Operation.EDIT_CONTENT, SEEDED_ID, version = 1),
            pendingAction(Operation.DELETE_OWNED, SEEDED_ID, version = Long.MAX_VALUE),
        ).forEach { action ->
            val prepared = pendingRecord(action)
            listOf(prepared, assertNotNull(prepared.markedDispatched())).forEach { record ->
                val encoded = encodePending(record)
                assertTrue(encoded.sameAs(encodePending(decodePending(encoded))))
            }
        }
        listOf(
            golden.replaceFirst("{", "{\"schemaVersion\":1,"),
            golden.replace("\"schemaVersion\":1,", ""),
            golden.replace("\"credentialVersion\":1", "\"schemaVersion\":1"),
            golden.replace("\"schemaVersion\":1", "\"unknown\":1"),
            golden.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            golden.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            golden.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            golden.replace(
                "\"credentialVersion\":1,\"localGeneration\":1",
                "\"localGeneration\":1,\"credentialVersion\":1",
            ),
            golden.replace("\"credentialVersion\":1", "\"credentialVersion\":\"1\""),
            golden.replace("\"fingerprintVersion\":1", "\"fingerprintVersion\":2"),
            golden.replace("\"parentId\":null", "\"parentId\":[]"),
            golden.replace("\"parentId\":null", "\"parentId\":false"),
            golden.replace("\"expectedVersion\":null", "\"expectedVersion\":\"1\""),
            golden.replace("PREPARED", "PR\\u0045PARED"),
            golden.replace("PREPARED", "COMPLETE"),
            golden.replace("CREATE_REPORT", "ADMIN_DELETE"),
            golden.dropLast(1) + " }",
            " $golden",
        ).forEach(::assertPendingCorrupt)
        assertSame(
            PendingComplaintCodecResult.Corrupt,
            PendingComplaintRecordCodec.decode(Fixtures.OTHER_ID, slot.bytes()),
        )
        val invalidUtf8 = byteArrayOf(0xc1.toByte(), 0xbb.toByte()) + golden.drop(1).encodeToByteArray()
        assertSame(PendingComplaintCodecResult.Corrupt, PendingComplaintRecordCodec.decode(slot.id, invalidUtf8))
        assertPendingCorrupt("\uFEFF$golden")
        val supplied = slot.bytes()
        val decoded =
            assertIs<PendingComplaintCodecResult.Value<PendingComplaintRecord>>(
                PendingComplaintRecordCodec.decode(slot.id, supplied),
            ).value
        supplied.fill(0)
        assertTrue(slot.sameAs(encodePending(decoded)))
        assertSame(
            PendingComplaintCodecResult.Corrupt,
            PendingComplaintRecordCodec.decode(slot.id, ByteArray(PendingComplaintSlot.MAX_BYTES)),
        )
        assertSame(
            PendingComplaintCodecResult.TooLarge,
            PendingComplaintRecordCodec.decode(
                slot.id,
                ByteArray(
                    PendingComplaintSlot.MAX_BYTES + 1,
                ),
            ),
        )
    }

    @Test
    fun receiptTimesPreserveNanosAndDeriveOnlyFromSuppliedSession() {
        val record = pendingRecord()
        val times = record.times
        assertEquals(testInstant(1_000, 123_456_789), times.createdAt)
        assertEquals(testInstant(2_000, 987_654_321), times.sessionIssuedAt)
        assertEquals(testInstant(606_800, 987_654_321), times.serverReceiptSafeUntil)
        listOf(testInstant(-50_000_000_000), testInstant(50_000_000_000)).forEach { deviceTime ->
            val skewed = assertNotNull(PendingComplaintTimes.checked(deviceTime, times.sessionIssuedAt))
            assertEquals(times.serverReceiptSafeUntil, skewed.serverReceiptSafeUntil)
            assertEquals(deviceTime, decodePending(encodePending(pendingRecord(times = skewed))).times.createdAt)
        }
        val beforeEpoch = assertNotNull(PendingComplaintTimes.checked(times.createdAt, testInstant(-1, 999_999_999)))
        assertEquals(testInstant(604_799, 999_999_999), beforeEpoch.serverReceiptSafeUntil)
        assertNull(pendingInstant(1, -1))
        assertNull(pendingInstant(1, 1_000_000_000))
        assertNull(pendingInstant(Long.MIN_VALUE, 0))
        assertNull(pendingInstant(Long.MAX_VALUE, 0))
        assertNull(PendingComplaintTimes.checked(times.createdAt, Instant.fromEpochSeconds(Long.MAX_VALUE)))
        val golden = pendingGolden()
        listOf(
            golden.replace("\"serverReceiptSafeUntilSeconds\":606800", "\"serverReceiptSafeUntilSeconds\":606801"),
            golden.replace("\"serverReceiptSafeUntilNanos\":987654321", "\"serverReceiptSafeUntilNanos\":987654320"),
            golden.replace("\"sessionIssuedAtSeconds\":2000", "\"sessionIssuedAtSeconds\":${Long.MAX_VALUE}"),
            golden.replace("\"sessionIssuedAtSeconds\":2000", "\"sessionIssuedAtSeconds\":9223372036854775808"),
            golden.replace("\"createdAtNanos\":123456789", "\"createdAtNanos\":-1"),
            golden.replace("\"sessionIssuedAtNanos\":987654321", "\"sessionIssuedAtNanos\":1000000000"),
            golden.replace("\"serverReceiptSafeUntilNanos\":987654321", "\"serverReceiptSafeUntilNanos\":1000000000"),
            golden.replace("\"createdAtSeconds\":1000", "\"createdAtSeconds\":\"1000\""),
        ).forEach(::assertPendingCorrupt)
    }

    @Test
    fun transitionsAreExactImmutableCasProposalsWithoutDispatchAuthority() {
        val current = Fixtures.record()
        val prepared = pendingRecord()
        val slot = encodePending(prepared)
        val original = slot.bytes()
        assertTrue(
            slot.sameAs(
                assertIs<PendingComplaintChange.CreateIfMissing>(Transitions.prepare(prepared, current)).replacement,
            ),
        )
        val issued = testInstant(3_000, 111_222_333)
        val rebase = assertIs<PendingComplaintChange.Replace>(Transitions.rebasePrepared(slot, current, issued))
        assertSame(slot, rebase.expected)
        val rebased = decodePending(rebase.replacement)
        assertEquals(PendingComplaintState.PREPARED, rebased.state)
        assertTrue(rebased.binding.matches(current))
        assertTrue(prepared.request.sameAs(rebased.request))
        assertEquals(prepared.times.createdAt, rebased.times.createdAt)
        assertEquals(issued, rebased.times.sessionIssuedAt)
        assertEquals(testInstant(607_800, 111_222_333), rebased.times.serverReceiptSafeUntil)
        val dispatch =
            assertIs<PendingComplaintChange.Replace>(
                Transitions.markMayHaveDispatched(rebase.replacement, current, prepared.request),
            )
        assertSame(rebase.replacement, dispatch.expected)
        val dispatched = decodePending(dispatch.replacement)
        assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, dispatched.state)
        assertTrue(dispatched.binding.matches(current))
        assertTrue(prepared.request.sameAs(dispatched.request))
        assertEquals(rebased.times.createdAt, dispatched.times.createdAt)
        assertEquals(rebased.times.sessionIssuedAt, dispatched.times.sessionIssuedAt)
        assertEquals(rebased.times.serverReceiptSafeUntil, dispatched.times.serverReceiptSafeUntil)
        assertSame(slot, assertIs<PendingComplaintChange.Delete>(Transitions.cancelPrepared(slot, current)).expected)
        assertRequestMismatches(slot, current)
        listOf(
            Transitions.prepare(dispatched, current),
            Transitions.rebasePrepared(dispatch.replacement, current, issued),
            Transitions.markMayHaveDispatched(dispatch.replacement, current, prepared.request),
        ).forEach { assertPendingHold(Hold.NOT_PREPARED, it) }
        assertPendingHold(Hold.DISPATCH_UNCERTAIN, Transitions.cancelPrepared(dispatch.replacement, current))
        assertPendingHold(
            Hold.INVALID_TIME,
            Transitions.rebasePrepared(slot, current, Instant.fromEpochSeconds(Long.MAX_VALUE)),
        )
        assertEquals(PendingComplaintState.PREPARED, prepared.state)
        assertContentEquals(original, slot.bytes())
    }

    @Test
    fun completeInventoryQuarantinesForeignOrCorruptSlotsAndNeverEvicts() {
        val current = Fixtures.record()
        val records =
            (0 until PendingComplaintSnapshot.MAX_SLOTS).map { index ->
                val prepared = pendingRecord(key = Fixtures.slot(index).id)
                if (index % 2 == 0) prepared else assertNotNull(prepared.markedDispatched())
            }
        val slots = records.map(::encodePending)
        val snapshot = PendingComplaintSnapshot.checked(slots).valid()
        val decoded = assertIs<PendingComplaintInventory.Decoded>(Transitions.inspect(snapshot, current))
        assertEquals(16, decoded.entries().size)
        assertEquals(slots.map { it.id }, decoded.entries().map { it.request.key })
        assertEquals(records.map { it.state }, decoded.entries().map { it.state })
        assertTrue(decoded.entries().all { it.times.serverReceiptSafeUntil == testInstant(606_800, 987_654_321) })
        foreignCredentials().forEach { foreign ->
            val foreignSlot = encodePending(pendingRecord(key = slots.last().id, current = foreign))
            val mixed = PendingComplaintSnapshot.checked(slots.dropLast(1) + foreignSlot).valid()
            assertEquals(
                Hold.STALE_BINDING,
                assertIs<PendingComplaintInventory.Quarantined>(Transitions.inspect(mixed, current)).reason,
            )
            assertPendingHold(Hold.STALE_BINDING, Transitions.prepare(records.first(), foreign))
        }
        val corrupt = PendingComplaintSlot.checked(slots.last().id, byteArrayOf(0)).valid()
        val damaged = PendingComplaintSnapshot.checked(slots.dropLast(1) + corrupt).valid()
        assertEquals(
            Hold.CORRUPT_SLOT,
            assertIs<PendingComplaintInventory.Quarantined>(Transitions.inspect(damaged, current)).reason,
        )
        assertPendingHold(Hold.CORRUPT_SLOT, Transitions.cancelPrepared(corrupt, current))
        listOf(current.beginDeletion(Fixtures.KEY).valid(), current.beginLocalReset().valid()).forEach { inactive ->
            assertEquals(
                Hold.INACTIVE_CREDENTIAL,
                assertIs<PendingComplaintInventory.Quarantined>(Transitions.inspect(snapshot, inactive)).reason,
            )
            assertPendingHold(Hold.INACTIVE_CREDENTIAL, Transitions.prepare(records.first(), inactive))
        }
        assertIs<PendingComplaintChange.Delete>(Transitions.cancelPrepared(slots.first(), current))
        assertIs<PendingComplaintChange.CreateIfMissing>(Transitions.prepare(pendingRecord(), current))
        val suppliedRecords = records.toMutableList()
        val retained = PendingComplaintInventory.Decoded(suppliedRecords)
        suppliedRecords.clear()
        snapshot
            .entries()
            .first()
            .bytes()
            .fill(0)
        assertEquals(16, retained.entries().size)
        assertEquals(16, snapshot.size)
        assertTrue(snapshot.entries().zip(slots).all { (actual, original) -> actual.sameAs(original) })
        assertEquals(
            16,
            assertIs<PendingComplaintInventory.Decoded>(Transitions.inspect(snapshot, current)).entries().size,
        )
    }
}

private fun pendingAction(
    operation: Operation = Operation.CREATE_REPORT,
    target: String = Fixtures.OTHER_ID,
    parent: String? = null,
    version: Long? = null,
): PendingComplaintAction = assertNotNull(PendingComplaintAction.checked(operation, target, parent, version))

private fun pendingRequest(
    action: PendingComplaintAction = pendingAction(),
    key: String = Fixtures.KEY,
    fingerprint: String = testFingerprint,
): PendingComplaintRequest =
    assertNotNull(
        PendingComplaintRequest.checked(
            action,
            key,
            assertNotNull(PendingComplaintFingerprint.checked(1, fingerprint)),
        ),
    )

private fun pendingRecord(
    action: PendingComplaintAction = pendingAction(),
    key: String = Fixtures.KEY,
    current: InstallationCredentialRecord = Fixtures.record(),
    times: PendingComplaintTimes =
        assertNotNull(PendingComplaintTimes.checked(testInstant(1_000, 123_456_789), testInstant(2_000, 987_654_321))),
): PendingComplaintRecord =
    PendingComplaintRecord.prepared(
        assertNotNull(
            PendingComplaintBinding.checked(
                current.material.installationId,
                current.credentialVersion,
                current.localGeneration,
                current.material.dataScopeId,
            ),
        ),
        pendingRequest(action, key),
        times,
    )

private fun encodePending(record: PendingComplaintRecord): PendingComplaintSlot =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintSlot>>(PendingComplaintRecordCodec.encode(record)).value

private fun decodePending(slot: PendingComplaintSlot): PendingComplaintRecord =
    assertIs<PendingComplaintCodecResult.Value<PendingComplaintRecord>>(PendingComplaintRecordCodec.decode(slot)).value

private fun assertPendingCorrupt(text: String) {
    assertSame(
        PendingComplaintCodecResult.Corrupt,
        PendingComplaintRecordCodec.decode(Fixtures.KEY, text.encodeToByteArray()),
    )
}

private fun assertPendingHold(
    expected: Hold,
    change: PendingComplaintChange,
) {
    assertEquals(expected, assertIs<PendingComplaintChange.Keep>(change).reason)
}

private fun assertRequestMismatches(
    slot: PendingComplaintSlot,
    current: InstallationCredentialRecord,
) {
    listOf(
        pendingRequest(key = Fixtures.OTHER_ID),
        pendingRequest(fingerprint = "A".repeat(43)),
        pendingRequest(action = pendingAction(target = Fixtures.ID)),
        pendingRequest(action = pendingAction(Operation.CREATE_REPLY, parent = SEEDED_ID)),
    ).forEach { assertPendingHold(Hold.REQUEST_MISMATCH, Transitions.markMayHaveDispatched(slot, current, it)) }
    val edit = encodePending(pendingRecord(pendingAction(Operation.EDIT_CONTENT, SEEDED_ID, version = 1)))
    val changedVersion = pendingRequest(pendingAction(Operation.EDIT_CONTENT, SEEDED_ID, version = 2))
    assertPendingHold(Hold.REQUEST_MISMATCH, Transitions.markMayHaveDispatched(edit, current, changedVersion))
    val reply = encodePending(pendingRecord(pendingAction(Operation.CREATE_REPLY, parent = SEEDED_ID)))
    val changedParent = pendingRequest(pendingAction(Operation.CREATE_REPLY, parent = Fixtures.ID))
    assertPendingHold(Hold.REQUEST_MISMATCH, Transitions.markMayHaveDispatched(reply, current, changedParent))
}

private fun foreignCredentials(): List<InstallationCredentialRecord> =
    listOf(
        Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID)),
        Fixtures.record(version = 2),
        Fixtures.record(generation = 2),
        Fixtures.record(material = Fixtures.material(scope = Fixtures.OTHER_ID)),
    )

private fun testInstant(
    seconds: Long,
    nanos: Int = 0,
): Instant = assertNotNull(pendingInstant(seconds, nanos))

private fun pendingGolden(): String =
    "{\"schemaVersion\":1,\"installationId\":\"${Fixtures.ID}\",\"credentialVersion\":1,\"localGeneration\":1," +
        "\"dataScopeId\":\"${Fixtures.SCOPE}\",\"operation\":\"CREATE_REPORT\"," +
        "\"targetId\":\"${Fixtures.OTHER_ID}\",\"parentId\":null," +
        "\"idempotencyKey\":\"${Fixtures.KEY}\",\"fingerprintVersion\":1," +
        "\"fingerprint\":\"$testFingerprint\",\"expectedVersion\":null," +
        "\"createdAtSeconds\":1000,\"createdAtNanos\":123456789," +
        "\"sessionIssuedAtSeconds\":2000,\"sessionIssuedAtNanos\":987654321," +
        "\"serverReceiptSafeUntilSeconds\":606800,\"serverReceiptSafeUntilNanos\":987654321,\"state\":\"PREPARED\"}"

private const val SEEDED_ID = "aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa"
private val testFingerprint = "Z".repeat(42) + "A"
