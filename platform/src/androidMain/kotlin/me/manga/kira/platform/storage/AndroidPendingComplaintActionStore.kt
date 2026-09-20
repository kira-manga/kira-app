package me.manga.kira.platform.storage

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * One fixed, backup-excluded AtomicFile for opaque pending slots; no credential or content semantics.
 * The coordinator owns recovery authorization. This adapter serializes only its single-process service.
 */
class AndroidPendingComplaintActionStore internal constructor(
    private val io: AndroidCredentialFileIo,
    private val dispatcher: CoroutineDispatcher,
) : PendingComplaintActionStore {
    constructor(context: Context) : this(AndroidNativeCredentialFileIo(context), Dispatchers.IO)

    override suspend fun read(): PendingReadResult =
        serialized<PendingReadResult>({ it }) { access ->
            PendingReadResult.Verified(access.read().snapshot)
        }

    override suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult =
        serialized<PendingCreateResult>({ it }) { access ->
            val current = access.read()
            val entries = current.snapshot.entries()
            if (entries.any { it.id == slot.id }) {
                PendingCreateResult.AlreadyPresent
            } else {
                access.write(pendingValue(PendingComplaintSnapshot.checked(entries + slot)), current.bytes)
                PendingCreateResult.Stored
            }
        }

    override suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult =
        serialized<PendingReplaceResult>({ it }) { access ->
            val current = access.read()
            val entries = current.snapshot.entries()
            val stored = entries.firstOrNull { it.id == expected.id }
            when {
                expected.id != replacement.id -> PendingReplaceResult.Stale
                stored == null -> PendingReplaceResult.Missing
                !stored.sameAs(expected) -> PendingReplaceResult.Stale
                else -> {
                    val updated = entries.map { if (it.id == expected.id) replacement else it }
                    access.write(pendingValue(PendingComplaintSnapshot.checked(updated)), current.bytes)
                    PendingReplaceResult.Stored
                }
            }
        }

    override suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult =
        serialized<PendingDeleteResult>({ it }) { access ->
            val current = access.read()
            val entries = current.snapshot.entries()
            val stored = entries.firstOrNull { it.id == expected.id }
            when {
                stored == null -> PendingDeleteResult.Missing
                !stored.sameAs(expected) -> PendingDeleteResult.Stale
                else -> {
                    val retained = entries.filterNot { it.id == expected.id }
                    access.write(pendingValue(PendingComplaintSnapshot.checked(retained)), current.bytes)
                    PendingDeleteResult.Deleted
                }
            }
        }

    override suspend fun clearForConfirmedRecovery(): PendingClearResult =
        serialized<PendingClearResult>({ it }) { access ->
            access.clear()
            PendingClearResult.Cleared
        }

    // Native exceptions cross this SPI only as content-free failures; cancellation keeps its identity.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> serialized(
        failed: (InstallationStorageFailure) -> T,
        operation: (AndroidPendingAccess) -> T,
    ): T =
        withContext(dispatcher) {
            PROCESS_LOCK.withLock {
                try {
                    val access = AndroidPendingAccess(io, currentCoroutineContext())
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

private class AndroidPendingAccess(
    io: AndroidCredentialFileIo,
    private val context: CoroutineContext,
) {
    val files = AndroidCredentialAtomicFile(io, ::check)

    fun check() = context.ensureActive()

    fun read(): AndroidPendingInventory {
        val pieces = files.pieces(SLOT)
        if (pieces.backup || (!pieces.base && pieces.pending)) {
            androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        }
        if (!pieces.base) {
            files.proveAbsent(SLOT)
            return AndroidPendingInventory(pendingValue(PendingComplaintSnapshot.checked(emptyList())), null)
        }
        val bytes = requiredBytes(AndroidCredentialVariant.BASE)
        val snapshot = AndroidPendingFrame.decode(bytes)
        if (pieces.pending) {
            requireDiscardablePending()
            files.discardPending(SLOT, bytes)
        }
        files.confirm(SLOT, bytes)
        return AndroidPendingInventory(snapshot, bytes)
    }

    fun write(
        snapshot: PendingComplaintSnapshot,
        previous: ByteArray?,
    ) {
        // The shared transaction compares and reads back the whole frame, including every retained slot.
        files.write(SLOT, AndroidPendingFrame.encode(snapshot), previous)
    }

    fun clear() {
        // Preflight every fixed variant before the first removal. One damaged piece cannot hide a
        // readable future schema or a protection/I/O/state-change failure in another piece.
        for (variant in AndroidCredentialVariant.entries) {
            try {
                files.read(SLOT, variant)?.let { AndroidPendingFrame.decode(it) }
            } catch (refused: AndroidCredentialProblem) {
                val reason = (refused.failure as? InstallationStorageFailure.PermanentFailure)?.reason
                if (reason !in RECOVERABLE_DAMAGE) throw refused
            }
        }
        files.removeAll(SLOT)
    }

    private fun requiredBytes(variant: AndroidCredentialVariant): ByteArray =
        files.read(SLOT, variant) ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)

    private fun requireDiscardablePending() {
        try {
            AndroidPendingFrame.requireSupportedSchema(requiredBytes(AndroidCredentialVariant.NEW))
        } catch (refused: AndroidCredentialProblem) {
            val reason = (refused.failure as? InstallationStorageFailure.PermanentFailure)?.reason
            // A validated old base may abandon an incomplete write, but never an observed future schema.
            if (reason != InstallationPermanentFailure.CORRUPT) throw refused
        }
    }

    companion object {
        private val SLOT = AndroidCredentialSlot.PENDING_ACTIONS
        private val RECOVERABLE_DAMAGE =
            setOf(InstallationPermanentFailure.CORRUPT, InstallationPermanentFailure.TOO_LARGE)
    }
}

private class AndroidPendingInventory(
    val snapshot: PendingComplaintSnapshot,
    val bytes: ByteArray?,
)

/**
 * Android physical frame v1, not the data-layer record codec: KPAS, u8 schema, u8 count, u32 logical
 * bytes; then sorted [36 ASCII UUID bytes, u16 length, opaque bytes] entries. Integers are big-endian.
 */
private object AndroidPendingFrame {
    private const val SCHEMA = 1
    private const val MAGIC_BYTES = 4
    private const val UUID_BYTES = 36
    private const val HEADER_BYTES = 10
    private const val ENTRY_PREFIX_BYTES = UUID_BYTES + Short.SIZE_BYTES
    private const val UNSIGNED_BYTE_MASK = 0xff
    private const val UNSIGNED_SHORT_MASK = 0xffff
    private const val UNSIGNED_INT_MASK = 0xffffffffL
    private val MAGIC = "KPAS".encodeToByteArray()

    fun encode(snapshot: PendingComplaintSnapshot): ByteArray {
        val entries = snapshot.entries().sortedBy { it.id }
        val size = HEADER_BYTES + entries.size * ENTRY_PREFIX_BYTES + snapshot.logicalBytes
        if (size > PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(SCHEMA.toByte())
        buffer.put(entries.size.toByte())
        buffer.putInt(snapshot.logicalBytes)
        for (entry in entries) {
            buffer.put(entry.id.encodeToByteArray())
            buffer.putShort(entry.size.toShort())
            buffer.put(entry.bytes())
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): PendingComplaintSnapshot {
        requireSupportedSchema(bytes)
        val entries = preflight(bytes)
        // No opaque payload is copied until every count, identity, length, sum and end has been checked.
        val slots =
            entries.map { entry ->
                pendingValue(
                    PendingComplaintSlot.checked(
                        entry.id,
                        bytes.copyOfRange(entry.offset, entry.offset + entry.size),
                    ),
                )
            }
        return pendingValue(PendingComplaintSnapshot.checked(slots))
    }

    fun requireSupportedSchema(bytes: ByteArray) {
        if (bytes.size > PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        if (bytes.size <= MAGIC_BYTES || MAGIC.indices.any { bytes[it] != MAGIC[it] }) {
            androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        }
        // Inspect the schema even when the rest of a future-version header is truncated.
        if (bytes[MAGIC_BYTES].toInt() != SCHEMA) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
    }

    private fun preflight(bytes: ByteArray): List<AndroidPendingFrameEntry> {
        if (bytes.size < HEADER_BYTES) androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(MAGIC_BYTES + Byte.SIZE_BYTES)
        val count = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        val logicalBytes = buffer.int.toLong() and UNSIGNED_INT_MASK
        if (count > PendingComplaintSnapshot.MAX_SLOTS || logicalBytes > PendingComplaintSnapshot.MAX_LOGICAL_BYTES) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        val entries = ArrayList<AndroidPendingFrameEntry>(count)
        var actualBytes = 0
        var previousId: String? = null
        repeat(count) {
            if (buffer.remaining() < ENTRY_PREFIX_BYTES) {
                androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
            }
            val id = String(ByteArray(UUID_BYTES).also { buffer.get(it) }, Charsets.US_ASCII)
            if (!canonicalInstallationUuid(id) || previousId?.let { it >= id } == true) {
                androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
            }
            val size = buffer.short.toInt() and UNSIGNED_SHORT_MASK
            if (size > PendingComplaintSlot.MAX_BYTES) {
                androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
            }
            actualBytes += size
            if (actualBytes > PendingComplaintSnapshot.MAX_LOGICAL_BYTES) {
                androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
            }
            if (size > buffer.remaining()) androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
            entries.add(AndroidPendingFrameEntry(id, buffer.position(), size))
            buffer.position(buffer.position() + size)
            previousId = id
        }
        if (actualBytes.toLong() != logicalBytes || buffer.hasRemaining()) {
            androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        }
        return entries
    }
}

private class AndroidPendingFrameEntry(
    val id: String,
    val offset: Int,
    val size: Int,
)

private fun <T> pendingValue(result: InstallationValueResult<T>): T =
    when (result) {
        is InstallationValueResult.Valid -> result.value
        is InstallationValueResult.Invalid ->
            androidCredentialPermanent(
                when (result.issue) {
                    InstallationValueIssue.SLOT_SIZE,
                    InstallationValueIssue.SLOT_COUNT,
                    InstallationValueIssue.TOTAL_SLOT_SIZE,
                    -> InstallationPermanentFailure.TOO_LARGE
                    else -> InstallationPermanentFailure.CORRUPT
                },
            )
    }
