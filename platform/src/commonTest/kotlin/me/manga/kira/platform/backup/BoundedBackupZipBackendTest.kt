@file:Suppress("MagicNumber")

package me.manga.kira.platform.backup

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.use

/** Three backend witnesses moved from desktop: actual target filesystem/Okio inflation, no provider or media decoding. */
class BoundedBackupZipBackendTest {
    private val fs = FileSystem.SYSTEM
    private val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bounded-backup-zip-${Random.nextLong().toULong()}")
        .also { fs.createDirectory(it, mustCreate = true) }
    private var nextFile = 0

    @AfterTest
    fun close() = fs.deleteRecursively(root)

    @Test
    fun storedAndDeflatedUtf8EntriesIncludingDirectoriesPassActualCrcChecks() {
        val entries = arrayOf("pages/" to ByteArray(0), "pages/صفحة.png" to ByteArray(9000) { it.toByte() })
        for (bytes in listOf(stored(*entries), BoundedBackupZipBackendFixtures.deflatedUtf8())) {
            open(bytes).use { zip ->
                assertEquals(2, zip.entries.size)
                for ((name, expected) in entries) {
                    assertContentEquals(expected, zip.readEntry(zip.entry(name), listOf(BackupByteBudget(expected.size.toLong()))) {})
                }
            }
        }
    }

    @Test
    fun crcOnlyPayloadCorruptionIsRejectedEvenWhenEverySizeStillMatches() {
        val bytes = stored("backup.json" to "{}".encodeToByteArray())
        bytes[30 + "backup.json".length] = '['.code.toByte()
        open(bytes).use { zip ->
            assertFailsWith<InvalidBackupArchive> { zip.readEntry(zip.entry("backup.json"), listOf(BackupByteBudget(100))) {} }
        }
    }

    @Test
    fun compressedSmallExpandedLargeEntryCannotBypassItsBudget() {
        val bytes = BoundedBackupZipBackendFixtures.expandedDeflate()
        open(bytes).use { zip ->
            assertFailsWith<BackupImportLimitExceeded> { zip.readEntry(zip.entry("backup.json"), listOf(BackupByteBudget(64))) {} }
        }
        // Lie consistently in the central record AND data descriptor. Actual decoding still fails.
        val central = bytes.u32(bytes.size - 6).toInt()
        bytes.put32(central + 24, 8)
        bytes.put32(central - 4, 8)
        open(bytes).use { zip ->
            assertFailsWith<IOException> { zip.readEntry(zip.entry("backup.json"), listOf(BackupByteBudget(64))) {} }
        }
    }

    private fun open(bytes: ByteArray): BoundedBackupZip {
        val path = root / "${nextFile++}.zip"
        fs.write(path, mustCreate = true) { write(bytes) }
        return BoundedBackupZip.open(fs, path, BackupZipLimits()) {}
    }

    private fun stored(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = Buffer()
        BackupZipWriter(buffer).apply {
            entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
            finish()
        }
        return buffer.readByteArray()
    }

    private fun ByteArray.put32(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
