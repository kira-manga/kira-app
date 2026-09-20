package me.manga.kira.platform.storage

import kotlin.test.fail

/** Synthetic structural examples only: these fixtures make no entropy/bootstrap claim. */
internal object InstallationValueFixtures {
    const val ID = "11111111-1111-4111-8111-111111111111"
    const val OTHER_ID = "22222222-2222-4222-a222-222222222222"
    const val KEY = "33333333-3333-4333-b333-333333333333"
    const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
    val secret = "A".repeat(42) + "E"

    fun material(
        id: String = ID,
        secret: String = this.secret,
        platform: String = "ANDROID",
        scope: String = LIVE_SCOPE,
    ): InstallationValueResult<InstallationCredentialMaterial> =
        InstallationCredentialMaterial.checked(
            id,
            secret,
            platform,
            scope,
        )

    fun record(
        version: Long = 1,
        generation: Long = 1,
        state: InstallationCredentialState = InstallationCredentialState.ACTIVE,
        key: String? = null,
        schema: Int = 1,
    ): InstallationValueResult<InstallationCredentialRecord> =
        InstallationCredentialRecord.checked(
            schema,
            material().valid(),
            version,
            generation,
            state,
            key,
        )

    fun slot(
        index: Int,
        bytes: ByteArray = byteArrayOf(1),
    ): PendingComplaintSlot =
        PendingComplaintSlot
            .checked(
                "11111111-1111-4111-8111-${index.toString(16).padStart(12, '0')}",
                bytes,
            ).valid()
}

internal fun <T> InstallationValueResult<T>.valid(): T =
    when (this) {
        is InstallationValueResult.Valid -> value
        is InstallationValueResult.Invalid -> fail("Unexpected checked-value rejection: $issue")
    }
