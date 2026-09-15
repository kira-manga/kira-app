@file:Suppress("MagicNumber")

package me.manga.kira.platform.backup

import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedBackupZipTest {
    private val fs = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("bounded-backup-zip-").toString().toPath()
    private var nextFile = 0

    @AfterTest
    fun close() = fs.deleteRecursively(root)

    @Test
    fun inputDirectoryAndEntryCapsRejectBeforeLocalHeadersAreInterpreted() {
        val bytes = stored("one" to byteArrayOf(1), "two" to byteArrayOf(2))
        bytes[0] = 0 // If the limit check were late this would be an invalid-header error instead.
        for (limits in listOf(
            BackupZipLimits(maxArchiveBytes = bytes.size.toLong() - 1),
            BackupZipLimits(maxDirectoryBytes = 1),
            BackupZipLimits(maxEntries = 1),
        )) {
            assertFailsWith<BackupImportLimitExceeded> { open(bytes, limits).close() }
        }
    }

    @Test
    fun implicitDirectoriesAlsoConsumeTheIndexBudget() {
        assertFailsWith<BackupImportLimitExceeded> {
            open(stored("a/b/c" to byteArrayOf(1)), BackupZipLimits(maxEntries = 2)).close()
        }
        open(stored("a/b" to byteArrayOf(1), "a/" to ByteArray(0))).use { assertEquals(2, it.entries.size) }
    }

    @Test
    fun duplicateTraversalAndPathAliasNamesAreRejected() {
        for (name in listOf("../x", "/x", "a/./b", "a/../b", "a//b", "a\\b", "C:/b", "bad\u0000")) {
            assertFailsWith<InvalidBackupArchive>(name) { open(stored(name to byteArrayOf(1))).close() }
        }
        for (names in listOf(listOf("a", "a"), listOf("a", "a/"), listOf("a", "a/b"), listOf("a/", "a/"))) {
            assertFailsWith<InvalidBackupArchive>(names.toString()) {
                open(stored(*names.map { it to ByteArray(0) }.toTypedArray())).close()
            }
        }
    }

    @Test
    fun centralAndLocalNamesAndCrcMustAgree() {
        val original = stored("backup.json" to byteArrayOf(1))
        for (field in listOf(16, 46)) {
            val bytes = original.copyOf()
            val offset = directoryOffset(bytes) + field
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            assertFailsWith<InvalidBackupArchive> { open(bytes).close() }
        }
    }

    @Test
    fun encryptionUnsupportedMethodsZip64MultidiskAndSymlinksAreRejected() {
        val original = stored("backup.json" to byteArrayOf(1))
        val central = directoryOffset(original)
        val end = original.size - 22
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { it.put16(6, 1); it.put16(central + 8, 1) },
            { it.put16(8, 12); it.put16(central + 10, 12) },
            { it.put16(4, 45); it.put16(central + 6, 45) },
            { it.put32(central + 24, -1) },
            { it.put16(end + 4, 1) },
            { it.put16(end + 8, 0) },
            { it.put16(end + 8, 65535); it.put16(end + 10, 65535) },
            { it.put32(central + 38, 0xA0000000.toInt()) },
            { it.put32(central + 38, 0x10) },
            { it[30] = 0xFF.toByte(); it[central + 46] = 0xFF.toByte() },
        )
        mutations.forEachIndexed { index, mutate ->
            assertFailsWith<InvalidBackupArchive>("mutation $index") { open(original.copyOf().also(mutate)).close() }
        }
    }

    @Test
    fun extraFieldsCannotIntroduceZip64EncryptionOrASecondNameInterpretation() {
        val original = stored("backup.json" to byteArrayOf(1))
        for (type in listOf(0x0001, 0x9901, 0x0017, 0x7075, 0x0008)) {
            val central = directoryOffset(original)
            val extra = ByteArray(4).also { it.put16(0, type) }
            val at = central + 46 + "backup.json".length
            val bytes = original.copyOfRange(0, at) + extra + original.copyOfRange(at, original.size)
            bytes.put16(central + 30, extra.size)
            bytes.put32(bytes.size - 10, (original.u32(original.size - 10) + extra.size).toInt())
            assertFailsWith<InvalidBackupArchive>("extra $type") { open(bytes).close() }
        }
    }

    @Test
    fun aLocalRecordMissingFromTheDirectoryIsNotIgnored() {
        val original = stored("hidden" to byteArrayOf(1), "backup.json" to byteArrayOf(2))
        val central = directoryOffset(original)
        val hiddenRecordSize = 46 + "hidden".length
        val bytes = original.copyOfRange(0, central) + original.copyOfRange(central + hiddenRecordSize, original.size)
        bytes.put16(bytes.size - 14, 1)
        bytes.put16(bytes.size - 12, 1)
        bytes.put32(bytes.size - 10, (original.u32(original.size - 10) - hiddenRecordSize).toInt())
        assertFailsWith<InvalidBackupArchive> { open(bytes).close() }
    }

    @Test
    fun truncatedArchiveTrailingGarbageAndDamagedDescriptorsAreRejected() {
        val original = deflated("backup.json" to byteArrayOf(1, 2, 3))
        val descriptor = directoryOffset(original) - 16
        val damaged = original.copyOf().also { it[descriptor + 4] = (it[descriptor + 4].toInt() xor 1).toByte() }
        for (bytes in listOf(original.copyOf(original.size - 1), original + byteArrayOf(0), damaged)) {
            assertFailsWith<InvalidBackupArchive> { open(bytes).close() }
        }
    }

    @Test
    fun laterEndSignatureIsRejectedBeforeOkioCanBuildAnotherIndex() {
        val original = stored("backup.json" to byteArrayOf(1))
        val laterEnd = original.copyOfRange(original.size - 22, original.size)
        // The real EOCD reaches EOF; the later signature declares zero comment but leaves a byte.
        assertRejectedBeforeOkioIndex(withComments(original, ByteArray(0), laterEnd + byteArrayOf(0)))
    }

    @Test
    fun zip64LocatorInCentralCommentIsRejectedEvenOutsideTheTailWindow() {
        val hidden = stored("hidden-a" to ByteArray(0), "hidden-b" to ByteArray(0))
        val hiddenDirectory = hidden.copyOfRange(directoryOffset(hidden), hidden.size - 22)
        val dataOffset = 30 + "backup.json".length
        val zip64End = ByteArray(56).apply {
            put32(0, 0x06064B50); put32(4, 44)
            put16(12, 45); put16(14, 45)
            put32(24, 2); put32(32, 2)
            put32(40, hiddenDirectory.size); put32(48, dataOffset + size)
        }
        val payload = zip64End + hiddenDirectory
        val original = stored("backup.json" to payload)
        val locator = ByteArray(20).apply {
            put32(0, 0x07064B50); put32(8, dataOffset); put32(16, 1)
        }
        for (commentSize in listOf(0, 65535)) {
            // At the maximum archive comment, the locator is just BEFORE the bounded tail read.
            val comment = ByteArray(commentSize)
            open(withComments(original, ByteArray(20), comment), BackupZipLimits(maxEntries = 1)).use { zip ->
                assertContentEquals(payload, zip.readEntry(zip.entry("backup.json"), listOf(BackupByteBudget(payload.size.toLong()))) {})
            }
            assertRejectedBeforeOkioIndex(withComments(original, locator, comment))
        }
    }

    @Test
    fun unsignedZip32DescriptorIsSupported() {
        val expected = byteArrayOf(1, 2, 3)
        val original = deflated("backup.json" to expected)
        val central = directoryOffset(original)
        val bytes = original.copyOfRange(0, central - 16) + original.copyOfRange(central - 12, original.size)
        bytes.put32(bytes.size - 6, central - 4)
        open(bytes).use { zip ->
            assertContentEquals(expected, zip.readEntry(zip.entry("backup.json"), listOf(BackupByteBudget(3))) {})
        }
    }

    private fun assertRejectedBeforeOkioIndex(bytes: ByteArray) {
        var opens = 0
        val observed = object : ForwardingFileSystem(fs) {
            override fun openReadOnly(file: Path): FileHandle {
                // Fail safely BEFORE any hidden index could be allocated if admission regresses.
                assertEquals(1, ++opens, "Only the structural reader may open a rejected archive")
                return super.openReadOnly(file)
            }
        }
        assertFailsWith<InvalidBackupArchive> { open(bytes, BackupZipLimits(maxEntries = 1), observed).close() }
        assertEquals(1, opens)
    }

    private fun withComments(original: ByteArray, centralComment: ByteArray, archiveComment: ByteArray): ByteArray {
        val end = original.size - 22
        val bytes = original.copyOfRange(0, end) + centralComment + original.copyOfRange(end, original.size) + archiveComment
        bytes.put16(directoryOffset(original) + 32, centralComment.size)
        bytes.put32(end + centralComment.size + 12, (original.u32(end + 12) + centralComment.size).toInt())
        bytes.put16(end + centralComment.size + 20, archiveComment.size)
        return bytes
    }

    private fun open(
        bytes: ByteArray,
        limits: BackupZipLimits = BackupZipLimits(),
        system: FileSystem = fs,
    ): BoundedBackupZip {
        val path = root / "${nextFile++}.zip"
        fs.write(path) { write(bytes) }
        return BoundedBackupZip.open(system, path, limits) {}
    }

    private fun stored(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = Buffer()
        BackupZipWriter(buffer).apply {
            entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
            finish()
        }
        return buffer.readByteArray()
    }

    private fun deflated(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun directoryOffset(bytes: ByteArray): Int = bytes.u32(bytes.size - 6).toInt()

    private fun ByteArray.put16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.put32(offset: Int, value: Int) {
        put16(offset, value)
        put16(offset + 2, value ushr 16)
    }
}
