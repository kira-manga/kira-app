@file:Suppress("MagicNumber")

package me.manga.kira.platform.backup

import me.manga.kira.core.error.AppError
import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class BackupByteBudgetTest {
    @Test
    fun exactLimitRequiresEofAndOneExtraByteNeverReachesTheSink() {
        val sink = Buffer()
        val budget = BackupByteBudget(4)
        assertEquals(4L, copyBackupBytes(Buffer().writeUtf8("1234"), sink, listOf(budget)) {})
        assertEquals("1234", sink.readUtf8())
        assertFailsWith<BackupImportLimitExceeded> {
            copyBackupBytes(Buffer().writeUtf8("5"), sink, listOf(budget)) {}
        }
        assertEquals(0L, sink.size)
        assertEquals(4L, budget.used)
    }

    @Test
    fun aggregateRejectionDoesNotChargeOtherBudgetsOrWriteTheFailingChunk() {
        val aggregate = BackupByteBudget(5)
        copyBackupBytes(Buffer().writeUtf8("abc"), Buffer(), listOf(aggregate)) {}
        val entry = BackupByteBudget(4)
        val sink = Buffer()
        assertFailsWith<BackupImportLimitExceeded> {
            copyBackupBytes(Buffer().writeUtf8("def"), sink, listOf(entry, aggregate)) {}
        }
        assertEquals(0L, entry.used)
        assertEquals(3L, aggregate.used)
        assertEquals(0L, sink.size)
    }

    @Test
    fun absentNegativeAndUnderreportedMetadataCannotGrantMoreBytes() {
        for (declared in listOf(null, -1L, 0L, 1L, 4L)) {
            val budget = BackupByteBudget(4)
            budget.checkDeclared(declared)
            assertFailsWith<BackupImportLimitExceeded>("declared=$declared") {
                copyBackupBytes(Buffer().writeUtf8("12345"), Buffer(), listOf(budget)) {}
            }
        }
        assertFailsWith<BackupImportLimitExceeded> { BackupByteBudget(4).checkDeclared(5) }
    }

    @Test
    fun longSizedCounterCannotOverflow() {
        val budget = BackupByteBudget(Long.MAX_VALUE)
        budget.consume(Long.MAX_VALUE)
        assertEquals(0L, budget.remaining)
        assertFailsWith<BackupImportLimitExceeded> { budget.consume(1) }
        assertFailsWith<BackupImportLimitExceeded> { budget.consume(-1) }
        assertEquals(Long.MAX_VALUE, budget.used)
    }

    @Test
    fun zeroProgressFailsInsteadOfSpinning() {
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = 0
        }
        assertFailsWith<IOException> { copyBackupBytes(source, Buffer(), listOf(BackupByteBudget(4))) {} }
    }

    @Test
    fun cancellationStopsBetweenChunksWithoutBeingConvertedToAnError() {
        val cancellation = CancellationException("test cancellation")
        var checkpoints = 0
        val sink = Buffer()
        val thrown = assertFailsWith<CancellationException> {
            copyBackupBytes(Buffer().write(ByteArray(9000)), sink, listOf(BackupByteBudget(9000))) {
                if (++checkpoints == 2) throw cancellation
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(8192L, sink.size)
        assertSame(cancellation, assertFailsWith<CancellationException> { backupImportError(cancellation) })
    }

    @Test
    fun resourceCorruptionAndStorageFailuresRetainDistinctTypes() {
        assertIs<AppError.Validation.OutOfRange>(backupImportError(BackupImportLimitExceeded()))
        assertIs<AppError.Validation.Format>(backupImportError(InvalidBackupArchive()))
        assertIs<AppError.Storage.Io>(backupImportError(IOException("disk full")))
        assertIs<AppError.Storage.Constraint>(backupImportError(BackupAcquisitionBusy()))
    }
}
