package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.platform.storage.CredentialCleanupMarker
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialMaterial
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Uses real production coordination on every call; only the two storage SPIs are replaced. */
internal class InstallationCoordinatorFixture(
    initial: InstallationCredentialRecord? = null,
) {
    val faults = InstallationStoreFaults()
    val credentials = InstallationCredentialStoreFake(faults)
    val pending = PendingComplaintActionStoreFake(faults)
    val coordinator = InstallationCredentialCoordinator(credentials, pending)

    init {
        if (initial != null) credentials.install(initial)
    }

    fun restart(): InstallationCredentialCoordinator = InstallationCredentialCoordinator(credentials, pending)

    fun assertAbsent() {
        assertFalse(credentials.keyPresent)
        assertFalse(credentials.payloadPresent)
        assertNull(credentials.marker)
        assertTrue(pending.slots.isEmpty())
    }
}

/** Fixed examples are intentionally not random, enrolled, bootstrap-validated or live credentials. */
internal object InstallationCoordinatorFixtures {
    const val ID = "11111111-1111-4111-8111-111111111111"
    const val OTHER_ID = "22222222-2222-4222-a222-222222222222"
    const val KEY = "33333333-3333-4333-b333-333333333333"
    const val SCOPE = "00000000-0000-0000-0000-000000000000"
    val secret = "A".repeat(42) + "E"

    fun material(
        id: String = ID,
        scope: String = SCOPE,
        secret: String = this.secret,
        platform: String = "ANDROID",
    ): InstallationCredentialMaterial = InstallationCredentialMaterial.checked(id, secret, platform, scope).valid()

    fun record(
        material: InstallationCredentialMaterial = material(),
        version: Long = 1,
        generation: Long = 1,
        state: InstallationCredentialState = InstallationCredentialState.ACTIVE,
        key: String? = null,
    ): InstallationCredentialRecord =
        InstallationCredentialRecord
            .checked(1, material, version, generation, state, key)
            .valid()

    fun marker(
        generation: Long?,
        reason: CredentialCleanupReason,
    ): CredentialCleanupMarker = CredentialCleanupMarker.checked(1, generation, reason).valid()

    fun slot(
        index: Int,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): PendingComplaintSlot =
        PendingComplaintSlot
            .checked(
                "44444444-4444-4444-8444-${index.toString(16).padStart(12, '0')}",
                bytes,
            ).valid()

    fun failures(): List<InstallationStorageFailure> =
        InstallationTemporaryFailure.entries.map { InstallationStorageFailure.TemporarilyUnavailable(it) } +
            InstallationPermanentFailure.entries.map { InstallationStorageFailure.PermanentFailure(it) }
}

internal fun <T> InstallationValueResult<T>.valid(): T =
    when (this) {
        is InstallationValueResult.Valid -> value
        is InstallationValueResult.Invalid -> fail("Unexpected checked-value rejection: $issue")
    }

internal fun <T> Outcome<T>.success(): T =
    when (this) {
        is Outcome.Success -> value
        else -> fail("Unexpected coordination result: $this")
    }

internal fun assertRefused(
    expected: Block,
    actual: Outcome<*>,
) {
    assertEquals(expected, assertIs<Outcome.Refused>(actual).reason)
}

internal fun assertStorageFailure(
    expected: InstallationStorageFailure,
    actual: Outcome<*>,
) {
    assertEquals(expected, assertIs<Outcome.StorageFailure>(actual).failure)
}
