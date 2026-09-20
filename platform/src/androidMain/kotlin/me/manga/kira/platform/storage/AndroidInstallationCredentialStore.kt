package me.manga.kira.platform.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.system.ErrnoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.CoroutineContext
import me.manga.kira.platform.storage.CredentialCleanupMarker as CleanupMarker

/**
 * Inert, single-process adapter: no DI, bootstrap, enrollment or pending-action integration.
 * The fixed key and credential-encrypted no-backup files are not a cross-process transaction.
 * Cleanup retains its independently readable marker until the coordinator separately removes it.
 */
class AndroidInstallationCredentialStore internal constructor(
    private val keys: AndroidCredentialKeyApi,
    private val io: AndroidCredentialFileIo,
    private val dispatcher: CoroutineDispatcher,
) : InstallationCredentialStore {
    constructor(context: Context) : this(
        AndroidNativeCredentialKey(),
        AndroidNativeCredentialFileIo(context),
        Dispatchers.IO,
    )

    override suspend fun read(): CredentialReadResult =
        serialized<CredentialReadResult>({ it }) { access ->
            access.contents.credential()?.let { CredentialReadResult.Present(it.record) }
                ?: CredentialReadResult.Missing
        }

    override suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult =
        serialized<CredentialCreateResult>({ it }) { access ->
            requireAndroid(record)
            if (!record.isInitialCandidate) androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
            access.contents.noMarker()
            if (access.contents.credential() != null) {
                access.contents.noMarker()
                CredentialCreateResult.AlreadyPresent
            } else {
                access.key { it.create() }
                if (!access.key { it.exists() }) {
                    androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                }
                access.contents.write(record, null)
                CredentialCreateResult.Stored
            }
        }

    override suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult =
        serialized<CredentialReplaceResult>({ it }) { access ->
            requireAndroid(record)
            access.contents.noMarker()
            val old = access.contents.credential()
            when {
                old == null -> CredentialReplaceResult.Missing
                old.record.localGeneration != expectedGeneration -> CredentialReplaceResult.Stale
                else -> {
                    requireReplacement(old.record, record)
                    access.contents.write(record, old)
                    CredentialReplaceResult.Stored
                }
            }
        }

    override suspend fun delete(
        expectedGeneration: Long,
        expectedMarker: CleanupMarker,
    ): CredentialDeleteResult =
        serialized<CredentialDeleteResult>({ it }) { access ->
            if (expectedGeneration <= 0 || expectedMarker.expectedGeneration != expectedGeneration) {
                CredentialDeleteResult.Stale
            } else {
                access.cleanup.finish(expectedMarker)
            }
        }

    override suspend fun resetUnreadableAfterConfirmation(expectedMarker: CleanupMarker): CredentialResetResult =
        serialized<CredentialResetResult>({ it }) { access ->
            if (expectedMarker.expectedGeneration != null) {
                androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
            }
            when (val result = access.cleanup.finish(expectedMarker)) {
                CredentialDeleteResult.Deleted -> CredentialResetResult.Deleted
                CredentialDeleteResult.Missing -> CredentialResetResult.Missing
                CredentialDeleteResult.Stale -> androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
                is InstallationStorageFailure -> result
            }
        }

    override suspend fun finishMarkedCleanup(expectedMarker: CleanupMarker): CredentialDeleteResult =
        serialized<CredentialDeleteResult>({ it }) { it.cleanup.finish(expectedMarker) }

    override suspend fun readCleanupMarker(): CleanupMarkerReadResult =
        serialized<CleanupMarkerReadResult>({ it }) { access ->
            access.contents.readMarker()?.let { CleanupMarkerReadResult.Present(it) } ?: CleanupMarkerReadResult.Missing
        }

    override suspend fun createCleanupMarkerIfMissing(marker: CleanupMarker): CleanupMarkerCreateResult =
        serialized<CleanupMarkerCreateResult>({ it }) { access ->
            val existing = access.contents.readMarker()
            if (existing != null) {
                if (!existing.sameAs(marker)) androidCredentialPermanent(InstallationPermanentFailure.MARKER_CONFLICT)
                CleanupMarkerCreateResult.AlreadyPresent
            } else {
                access.cleanup.requireNewMarkerTarget(marker)
                val bytes = codecValue(CredentialCleanupMarkerCodec.encode(marker)).copyBytes()
                access.files.write(AndroidCredentialSlot.MARKER, bytes, null)
                access.contents.requireMarker(marker)
                CleanupMarkerCreateResult.Stored
            }
        }

    override suspend fun removeCleanupMarker(expectedMarker: CleanupMarker): CleanupMarkerRemoveResult =
        serialized<CleanupMarkerRemoveResult>({ it }) { access ->
            access.contents.absentCredential()
            val stored = access.contents.readMarker()
            when {
                stored == null -> CleanupMarkerRemoveResult.Missing
                !stored.sameAs(expectedMarker) -> CleanupMarkerRemoveResult.Stale
                else -> {
                    access.files.removeAll(AndroidCredentialSlot.MARKER)
                    access.contents.absentCredential()
                    if (access.contents.readMarker() != null) {
                        androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                    }
                    CleanupMarkerRemoveResult.Removed
                }
            }
        }

    // This SPI boundary maps all native exceptions without exposing their causes or messages.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> serialized(
        failed: (InstallationStorageFailure) -> T,
        operation: (AndroidCredentialAccess) -> T,
    ): T =
        withContext(dispatcher) {
            PROCESS_LOCK.withLock {
                try {
                    val access = AndroidCredentialAccess(keys, io, currentCoroutineContext())
                    access.files.prepare()
                    operation(access).also { access.check() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed(androidCredentialFailure(failure))
                }
            }
        }

    companion object {
        private val PROCESS_LOCK = Mutex()
    }
}

private class AndroidCredentialAccess(
    private val keys: AndroidCredentialKeyApi,
    io: AndroidCredentialFileIo,
    private val context: CoroutineContext,
) {
    val files = AndroidCredentialAtomicFile(io, ::check)
    val contents = AndroidCredentialContents(this)
    val cleanup = AndroidCredentialCleanup(this)

    fun check() = context.ensureActive()

    fun <T> key(operation: (AndroidCredentialKeyApi) -> T): T {
        check()
        return operation(keys).also { check() }
    }
}

/** Checked records and independently readable markers over the fixed native pieces. */
private class AndroidCredentialContents(
    private val access: AndroidCredentialAccess,
) {
    fun credential(): AndroidCredentialValue? {
        val pieces = access.files.pieces(AndroidCredentialSlot.CREDENTIAL)
        // Probe even damaged files: a temporary/protection failure must not authorize their removal.
        val hasKey = access.key { it.exists() }
        if (pieces.absent && !hasKey) {
            absentCredential()
            return null
        }
        if (!hasKey) androidCredentialPermanent(InstallationPermanentFailure.INVALIDATED)
        if (!pieces.base || pieces.backup) androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        val bytes =
            access.files.read(AndroidCredentialSlot.CREDENTIAL, AndroidCredentialVariant.BASE)
                ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)
        val record = open(bytes)
        requireAndroid(record)
        // A valid old base is authority to abandon an interrupted replacement, never the converse.
        if (pieces.pending) access.files.discardPending(AndroidCredentialSlot.CREDENTIAL, bytes)
        access.files.confirm(AndroidCredentialSlot.CREDENTIAL, bytes)
        return AndroidCredentialValue(record, bytes)
    }

    fun readMarker(): CleanupMarker? {
        val slot = AndroidCredentialSlot.MARKER
        val pieces = access.files.pieces(slot)
        if (pieces.backup || (!pieces.base && pieces.pending)) {
            androidCredentialPermanent(InstallationPermanentFailure.MARKER_CONFLICT)
        }
        if (!pieces.base) {
            access.files.proveAbsent(slot)
            return null
        }
        val bytes =
            access.files.read(slot, AndroidCredentialVariant.BASE)
                ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)
        val value = codecValue(CredentialCleanupMarkerCodec.decode(bytes))
        if (pieces.pending) {
            val pending = access.files.read(slot, AndroidCredentialVariant.NEW)
            if (pending == null || !pending.contentEquals(bytes)) {
                androidCredentialPermanent(InstallationPermanentFailure.MARKER_CONFLICT)
            }
            access.files.discardPending(slot, bytes)
        }
        access.files.confirm(slot, bytes)
        return value
    }

    fun write(
        record: InstallationCredentialRecord,
        previous: AndroidCredentialValue?,
    ) {
        val plaintext = codecValue(InstallationCredentialCodec.encode(record)).copyBytes()
        val envelope =
            try {
                val encrypted = access.key { it.encrypt(plaintext) }
                AndroidCredentialEnvelope.pack(encrypted, plaintext.size, previous?.envelope)
            } finally {
                plaintext.fill(0)
            }
        noMarker()
        access.files.write(AndroidCredentialSlot.CREDENTIAL, envelope, previous?.envelope)
        val stored = credential() ?: androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        if (!stored.record.sameAs(record)) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        noMarker()
    }

    private fun open(bytes: ByteArray): InstallationCredentialRecord {
        val encrypted = AndroidCredentialEnvelope.unpack(bytes)
        val plaintext = access.key { it.decrypt(encrypted) }
        return try {
            codecValue(InstallationCredentialCodec.decode(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    // Only explicitly classified permanent native damage can authorize confirmed cleanup.
    @Suppress("TooGenericExceptionCaught")
    fun cleanupState(): AndroidCredentialCleanupState =
        try {
            val current = credential()
            AndroidCredentialCleanupState(current, absent = current == null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: AndroidCredentialProblem) {
            requireRecoverable(refused.failure)
            AndroidCredentialCleanupState(null, absent = false)
        } catch (failure: Exception) {
            requireRecoverable(androidCredentialFailure(failure))
            AndroidCredentialCleanupState(null, absent = false)
        }

    private fun requireRecoverable(failure: InstallationStorageFailure) {
        val reason = (failure as? InstallationStorageFailure.PermanentFailure)?.reason
        if (reason !in RECOVERABLE_DAMAGE) throw AndroidCredentialProblem(failure)
    }

    fun absentCredential() {
        access.files.proveAbsent(AndroidCredentialSlot.CREDENTIAL)
        if (access.key { it.exists() }) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }

    fun noMarker() {
        if (readMarker() != null) androidCredentialPermanent(InstallationPermanentFailure.MARKER_CONFLICT)
    }

    fun requireMarker(expected: CleanupMarker) {
        val stored = readMarker()
        if (stored == null || !stored.sameAs(expected)) {
            androidCredentialPermanent(InstallationPermanentFailure.MARKER_CONFLICT)
        }
    }

    companion object {
        private val RECOVERABLE_DAMAGE =
            setOf(
                InstallationPermanentFailure.CORRUPT,
                InstallationPermanentFailure.INVALIDATED,
                InstallationPermanentFailure.TOO_LARGE,
            )
    }
}

/** Marker authority is deliberately separate from content parsing and remains after partial cleanup. */
private class AndroidCredentialCleanup(
    private val access: AndroidCredentialAccess,
) {
    fun finish(expected: CleanupMarker): CredentialDeleteResult {
        val stored = access.contents.readMarker()
        return if (stored == null || !stored.sameAs(expected)) {
            CredentialDeleteResult.Stale
        } else {
            finishMatching(expected)
        }
    }

    private fun finishMatching(expected: CleanupMarker): CredentialDeleteResult {
        val current = access.contents.cleanupState()
        val readable = current.readable
        return when {
            readable != null && !matchesCleanup(readable.record, expected) -> CredentialDeleteResult.Stale
            current.absent -> {
                access.contents.absentCredential()
                access.contents.requireMarker(expected)
                CredentialDeleteResult.Missing
            }
            else -> {
                // Exact durable marker remains authority after key deletion makes the envelope unreadable.
                access.contents.requireMarker(expected)
                access.key { it.delete() }
                if (access.key { it.exists() }) {
                    androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                }
                access.files.removeAll(AndroidCredentialSlot.CREDENTIAL)
                access.contents.absentCredential()
                access.contents.requireMarker(expected)
                CredentialDeleteResult.Deleted
            }
        }
    }

    fun requireNewMarkerTarget(expected: CleanupMarker) {
        val current = access.contents.cleanupState().readable
        val matches =
            if (current == null) {
                expected.expectedGeneration == null
            } else {
                matchesCleanup(current.record, expected)
            }
        if (!matches) androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
    }
}

private class AndroidCredentialValue(
    val record: InstallationCredentialRecord,
    val envelope: ByteArray,
)

private class AndroidCredentialCleanupState(
    val readable: AndroidCredentialValue?,
    val absent: Boolean,
)

private fun requireAndroid(record: InstallationCredentialRecord) {
    if (record.material.platform != InstallationPlatform.ANDROID) {
        androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
    }
}

private fun requireReplacement(
    old: InstallationCredentialRecord,
    replacement: InstallationCredentialRecord,
) {
    val transition =
        when (replacement.state) {
            InstallationCredentialState.ACTIVE -> androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
            InstallationCredentialState.LOCAL_RESET_PENDING -> old.beginLocalReset()
            InstallationCredentialState.DELETION_PENDING ->
                old.beginDeletion(
                    replacement.pendingDeletionKey
                        ?: androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED),
                )
        }
    if (transition !is InstallationValueResult.Valid || !transition.value.sameAs(replacement)) {
        androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
    }
}

private fun matchesCleanup(
    record: InstallationCredentialRecord,
    marker: CleanupMarker,
): Boolean {
    if (record.localGeneration != marker.expectedGeneration) return false
    return when (marker.reason) {
        CredentialCleanupReason.USER_RESET_CONFIRMED -> record.state == InstallationCredentialState.LOCAL_RESET_PENDING
        CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED,
        CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED,
        -> record.state == InstallationCredentialState.DELETION_PENDING
        CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED -> false
    }
}

private fun <T> codecValue(result: InstallationCodecResult<T>): T =
    when (result) {
        is InstallationCodecResult.Value -> result.value
        InstallationCodecResult.Corrupt -> androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        InstallationCodecResult.TooLarge -> androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
    }

/** Small primitive bridge; host doubles do not stand in for Android Keystore qualification. */
internal interface AndroidCredentialKeyApi {
    fun exists(): Boolean

    fun create()

    fun encrypt(plaintext: ByteArray): AndroidCredentialCiphertext

    fun decrypt(encrypted: AndroidCredentialCiphertext): ByteArray

    fun delete()
}

internal class AndroidCredentialCiphertext(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object AndroidCredentialEnvelope {
    const val MAX_BYTES = 4096
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
    private const val HEADER_BYTES = 5
    private const val SCHEMA = 1
    private const val MAGIC_TEXT = "KICR"
    private val MAGIC = MAGIC_TEXT.encodeToByteArray()

    fun pack(
        encrypted: AndroidCredentialCiphertext,
        plaintextSize: Int,
        previous: ByteArray?,
    ): ByteArray {
        if (encrypted.iv.size != IV_BYTES || encrypted.ciphertext.size != plaintextSize + TAG_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
        if (plaintextSize >
            InstallationCredentialCodec.MAX_ENCODED_BYTES
        ) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        if (previous != null && encrypted.iv.contentEquals(unpack(previous).iv)) {
            androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
        return ByteArray(HEADER_BYTES + IV_BYTES + encrypted.ciphertext.size).also { bytes ->
            MAGIC.copyInto(bytes)
            bytes[MAGIC.size] = SCHEMA.toByte()
            encrypted.iv.copyInto(bytes, HEADER_BYTES)
            encrypted.ciphertext.copyInto(bytes, HEADER_BYTES + IV_BYTES)
        }
    }

    fun unpack(bytes: ByteArray): AndroidCredentialCiphertext {
        if (bytes.size > MAX_BYTES) androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        if (bytes.size < HEADER_BYTES + IV_BYTES + TAG_BYTES || MAGIC.indices.any { bytes[it] != MAGIC[it] }) {
            androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        }
        if (bytes[MAGIC.size].toInt() != SCHEMA) androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        if (bytes.size - HEADER_BYTES - IV_BYTES - TAG_BYTES > InstallationCredentialCodec.MAX_ENCODED_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        return AndroidCredentialCiphertext(
            bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES),
            bytes.copyOfRange(HEADER_BYTES + IV_BYTES, bytes.size),
        )
    }
}

private class AndroidNativeCredentialKey : AndroidCredentialKeyApi {
    override fun exists(): Boolean {
        val store = store()
        if (!store.containsAlias(SERVICE)) return false
        key(store)
        return true
    }

    override fun create() {
        if (store().containsAlias(SERVICE)) androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
        val parameters =
            KeyGenParameterSpec
                .Builder(SERVICE, PURPOSES)
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(parameters, SecureRandom())
            generateKey()
        }
        if (!exists()) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }

    override fun encrypt(plaintext: ByteArray): AndroidCredentialCiphertext {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Supplying an encryption IV is forbidden by randomized-encryption-required Keystore keys.
        cipher.init(Cipher.ENCRYPT_MODE, key(store()), SecureRandom())
        val iv = cipher.iv ?: androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        if (iv.size != AndroidCredentialEnvelope.IV_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
        cipher.updateAAD(AAD)
        return AndroidCredentialCiphertext(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(encrypted: AndroidCredentialCiphertext): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(store()), GCMParameterSpec(TAG_BITS, encrypted.iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(encrypted.ciphertext)
    }

    override fun delete() {
        store().deleteEntry(SERVICE)
    }

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(store: KeyStore): SecretKey {
        val key =
            store.getKey(SERVICE, null)
                ?: androidCredentialPermanent(InstallationPermanentFailure.INVALIDATED)
        if (key !is SecretKey) androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        val info =
            SecretKeyFactory
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val cipherPolicy =
            info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) &&
                info.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE))
        val keyPolicy =
            info.keySize == KEY_BITS &&
                info.purposes == PURPOSES &&
                info.origin == KeyProperties.ORIGIN_GENERATED &&
                !info.isUserAuthenticationRequired
        if (!cipherPolicy || !keyPolicy) androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        return key
    }

    companion object {
        private const val SERVICE = "me.manga.kira.complaint.installation.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val PURPOSES = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        private val AAD = "$SERVICE|credential|schema=1".encodeToByteArray()
    }
}

/** Content-free internal control flow only; neither native causes nor their messages escape the SPI. */
internal class AndroidCredentialProblem(
    val failure: InstallationStorageFailure,
) : Exception()

internal fun androidCredentialPermanent(reason: InstallationPermanentFailure): Nothing =
    throw AndroidCredentialProblem(InstallationStorageFailure.PermanentFailure(reason))

internal fun androidCredentialTemporary(reason: InstallationTemporaryFailure): Nothing =
    throw AndroidCredentialProblem(InstallationStorageFailure.TemporarilyUnavailable(reason))

internal fun androidCredentialFailure(failure: Exception): InstallationStorageFailure =
    when (failure) {
        is CancellationException -> throw failure
        is AndroidCredentialProblem -> failure.failure
        is UserNotAuthenticatedException ->
            InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED)
        is KeyPermanentlyInvalidatedException ->
            InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.INVALIDATED)
        is AEADBadTagException, is BadPaddingException ->
            InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
        is NoSuchAlgorithmException, is NoSuchProviderException, is NoSuchPaddingException, is SecurityException ->
            InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)
        is IOException, is ErrnoException ->
            InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.IO_FAILURE)
        else -> InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN)
    }
