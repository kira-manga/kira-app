package me.manga.kira.platform.storage

/** The two shipping installation platforms; Desktop must return Unsupported instead. */
enum class InstallationPlatform {
    ANDROID,
    IOS,
}

/** Durable local lifecycle. Neither pending state can be changed back to ACTIVE. */
enum class InstallationCredentialState {
    ACTIVE,
    DELETION_PENDING,
    LOCAL_RESET_PENDING,
}

/**
 * Explicit, structurally checked material from a future trusted bootstrap/CSPRNG producer.
 * Validation proves neither entropy nor bootstrap authenticity. No identifier or scope is inferred.
 */
class InstallationCredentialMaterial private constructor(
    val installationId: String,
    val secret: String,
    val platform: InstallationPlatform,
    val dataScopeId: String,
) {
    /** Compares all immutable material, including the secret, without exposing it in diagnostics. */
    fun sameAs(other: InstallationCredentialMaterial): Boolean =
        installationId == other.installationId &&
            secret == other.secret &&
            platform == other.platform &&
            dataScopeId == other.dataScopeId

    override fun toString(): String = "InstallationCredentialMaterial(redacted)"

    companion object {
        /** Checks canonical text without retaining invalid input in the failure result. */
        fun checked(
            installationId: String,
            secret: String,
            platform: String,
            dataScopeId: String,
        ): InstallationValueResult<InstallationCredentialMaterial> {
            val identityIssue =
                when {
                    !canonicalInstallationUuid(installationId) -> InstallationValueIssue.INSTALLATION_ID
                    !canonicalInstallationSecret(secret) -> InstallationValueIssue.SECRET
                    else -> null
                }
            if (identityIssue != null) return invalid(identityIssue)
            val checkedPlatform = InstallationPlatform.entries.firstOrNull { it.name == platform }
            return when {
                checkedPlatform == null -> invalid(InstallationValueIssue.PLATFORM)
                !canonicalInstallationScope(dataScopeId) -> invalid(InstallationValueIssue.SCOPE)
                else ->
                    InstallationValueResult.Valid(
                        InstallationCredentialMaterial(installationId, secret, checkedPlatform, dataScopeId),
                    )
            }
        }
    }
}

/**
 * Immutable checked record. There is deliberately no public constructor or data-class copy.
 * Native codecs must use [checked]; every lifecycle replacement retains the complete material.
 */
class InstallationCredentialRecord private constructor(
    val material: InstallationCredentialMaterial,
    val credentialVersion: Long,
    val localGeneration: Long,
    val state: InstallationCredentialState,
    val pendingDeletionKey: String?,
) {
    val schemaVersion: Int get() = SCHEMA_VERSION

    /** The only shape that may be passed to createIfMissing. */
    val isInitialCandidate: Boolean
        get() =
            credentialVersion == INITIAL_VERSION &&
                localGeneration == INITIAL_VERSION &&
                state == InstallationCredentialState.ACTIVE &&
                pendingDeletionKey == null

    /** Compares the complete record for closed-file/item read-back, not only its generation. */
    fun sameAs(other: InstallationCredentialRecord): Boolean =
        material.sameAs(other.material) &&
            credentialVersion == other.credentialVersion &&
            localGeneration == other.localGeneration &&
            state == other.state &&
            pendingDeletionKey == other.pendingDeletionKey

    /** Exact public binding used to fence late responses; a new identity can also have generation 1. */
    fun sameBinding(other: InstallationCredentialRecord): Boolean =
        material.installationId == other.material.installationId &&
            credentialVersion == other.credentialVersion &&
            localGeneration == other.localGeneration &&
            material.dataScopeId == other.material.dataScopeId

    /** Persist this transition before admitting a delete-all request; it does not authorize cleanup. */
    fun beginDeletion(key: String): InstallationValueResult<InstallationCredentialRecord> =
        transition(InstallationCredentialState.DELETION_PENDING, key)

    /** Only explicit warned local reset may request this irreversible local transition. */
    fun beginLocalReset(): InstallationValueResult<InstallationCredentialRecord> =
        transition(InstallationCredentialState.LOCAL_RESET_PENDING, null)

    private fun transition(
        target: InstallationCredentialState,
        key: String?,
    ): InstallationValueResult<InstallationCredentialRecord> =
        when {
            state != InstallationCredentialState.ACTIVE -> invalid(InstallationValueIssue.TRANSITION)
            localGeneration == Long.MAX_VALUE -> invalid(InstallationValueIssue.GENERATION_OVERFLOW)
            else -> checked(SCHEMA_VERSION, material, credentialVersion, localGeneration + 1, target, key)
        }

    override fun toString(): String = "InstallationCredentialRecord(redacted)"

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val INITIAL_VERSION: Long = 1

        /** Explicit checked material starts at version/generation 1; this creates no storage or identity. */
        fun candidate(material: InstallationCredentialMaterial): InstallationCredentialRecord =
            InstallationCredentialRecord(
                material,
                INITIAL_VERSION,
                INITIAL_VERSION,
                InstallationCredentialState.ACTIVE,
                null,
            )

        /**
         * Decoded native records are validated without permitting a mutable lifecycle copy.
         * Six explicit decoded fields keep this validation boundary separate from the closed constructor.
         */
        @Suppress("LongParameterList")
        fun checked(
            schemaVersion: Int,
            material: InstallationCredentialMaterial,
            credentialVersion: Long,
            localGeneration: Long,
            state: InstallationCredentialState,
            pendingDeletionKey: String?,
        ): InstallationValueResult<InstallationCredentialRecord> =
            when {
                schemaVersion != SCHEMA_VERSION -> invalid(InstallationValueIssue.SCHEMA)
                credentialVersion <= 0 -> invalid(InstallationValueIssue.VERSION)
                localGeneration <= 0 -> invalid(InstallationValueIssue.GENERATION)
                state == InstallationCredentialState.DELETION_PENDING &&
                    (pendingDeletionKey == null || !canonicalInstallationUuid(pendingDeletionKey)) ->
                    invalid(InstallationValueIssue.STATE_KEY)
                state != InstallationCredentialState.DELETION_PENDING && pendingDeletionKey != null ->
                    invalid(InstallationValueIssue.STATE_KEY)
                else ->
                    InstallationValueResult.Valid(
                        InstallationCredentialRecord(
                            material,
                            credentialVersion,
                            localGeneration,
                            state,
                            pendingDeletionKey,
                        ),
                    )
            }
    }
}

internal fun invalid(issue: InstallationValueIssue): InstallationValueResult.Invalid =
    InstallationValueResult.Invalid(
        issue,
    )

internal fun canonicalInstallationUuid(value: String): Boolean =
    value.length == UUID_LENGTH &&
        CANONICAL_V4.matches(value)

internal fun canonicalInstallationScope(value: String): Boolean =
    value == LIVE_SCOPE ||
        canonicalInstallationUuid(value)

// 43 sextets encode exactly 32 bytes and two padding bits. Those unused bits must be zero.
private fun canonicalInstallationSecret(value: String): Boolean =
    value.length == SECRET_TEXT_LENGTH &&
        !value.any { it !in BASE64URL } &&
        BASE64URL.indexOf(value.last()) % CANONICAL_TAIL_DIVISOR == 0

private const val UUID_LENGTH = 36
private const val SECRET_TEXT_LENGTH = 43
private const val CANONICAL_TAIL_DIVISOR = 4
private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val CANONICAL_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
