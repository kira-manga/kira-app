package me.manga.kira.platform.backup

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trip tests for [BackupZipWriter]: archives it writes must be readable back through the
 * exact reader the app uses (okio `openZip` — the DefaultCbzReader/import path), byte-identical,
 * and the ZIP32 guards must fail typed rather than emit a corrupt archive.
 */
class BackupZipWriterTest {

    private val fs = FileSystem.SYSTEM
    private val created = mutableListOf<Path>()

    private fun tempFile(name: String): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "backup-zip-test-${Random.nextLong()}-$name"
        created += path
        return path
    }

    @AfterTest
    fun cleanUp() {
        created.forEach { fs.delete(it, mustExist = false) }
    }

    private fun writeArchive(
        target: Path,
        block: BackupZipWriter.() -> Unit,
    ) {
        fs.sink(target).buffer().use { sink ->
            val writer = BackupZipWriter(sink)
            writer.block()
            writer.finish()
        }
    }

    @Test
    fun bytes_and_file_entries_read_back_byte_identical_via_openZip() {
        val manifest = """{"formatVersion":1}""".encodeToByteArray()
        // Bigger than one 64 KiB chunk so the streaming path crosses chunk boundaries.
        val cbzBytes = Random(seed = 42).nextBytes(150 * 1024)
        val cbzSource = tempFile("source.cbz")
        fs.write(cbzSource) { write(cbzBytes) }

        val archive = tempFile("archive.zip")
        writeArchive(archive) {
            writeEntryBytes("backup.json", manifest)
            writeEntryFromFile("downloads/0.cbz", fs, cbzSource)
        }

        val zipFs = fs.openZip(archive)
        val entries = zipFs.list("/".toPath()).map { it.toString() }.sorted()
        assertEquals(listOf("/backup.json", "/downloads"), entries, "top-level archive listing")

        val manifestBack = zipFs.read("/backup.json".toPath()) { readByteArray() }
        assertContentEquals(manifest, manifestBack)

        val cbzBack = zipFs.read("/downloads/0.cbz".toPath()) { readByteArray() }
        assertContentEquals(cbzBytes, cbzBack, "streamed entry is byte-identical")
    }

    @Test
    fun crc32_matches_the_jvm_reference_implementation() {
        for (payload in listOf(
            ByteArray(0),
            "hello backup".encodeToByteArray(),
            Random(seed = 7).nextBytes(70 * 1024),
        )) {
            val reference = java.util.zip.CRC32().apply { update(payload) }.value.toInt()
            val ours = Crc32().apply { update(payload) }.value
            assertEquals(reference, ours, "CRC-32 of ${payload.size} bytes")
        }
    }

    @Test
    fun empty_archive_is_still_a_valid_zip() {
        val archive = tempFile("empty.zip")
        writeArchive(archive) { }

        val zipFs = fs.openZip(archive)
        assertTrue(zipFs.list("/".toPath()).isEmpty())
    }

    @Test
    fun entry_count_overflow_fails_typed_before_corrupting_the_archive() {
        val archive = tempFile("overflow.zip")
        fs.sink(archive).buffer().use { sink ->
            val writer = BackupZipWriter(sink)
            repeat(BackupZipWriter.MAX_ZIP32_ENTRIES) { n ->
                writer.writeEntryBytes("e$n", ByteArray(1))
            }
            assertFailsWith<ZipLimitExceededException> {
                writer.writeEntryBytes("one-too-many", ByteArray(1))
            }
        }
    }

    @Test
    fun finish_is_single_shot_and_seals_the_writer() {
        val archive = tempFile("sealed.zip")
        fs.sink(archive).buffer().use { sink ->
            val writer = BackupZipWriter(sink)
            writer.writeEntryBytes("backup.json", byteArrayOf(1))
            writer.finish()
            assertFailsWith<IllegalStateException> { writer.finish() }
            assertFailsWith<IllegalStateException> { writer.writeEntryBytes("late", byteArrayOf(2)) }
        }
    }

}
