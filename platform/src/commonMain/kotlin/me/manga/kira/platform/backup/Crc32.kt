package me.manga.kira.platform.backup

/**
 * Incremental table-based CRC-32 (IEEE 802.3 / reflected polynomial 0xEDB88320).
 *
 * commonMain twin of the one-shot `crc32()` in iosMain's StoreZipWriter, reshaped as an
 * accumulator so [BackupZipWriter] can hash a file in streamed chunks without ever holding the
 * whole entry in memory. Matches the CRC stored in every ZIP local-file/central-directory header
 * (okio's `openZip` reader validates it).
 */
internal class Crc32 {
    private var crc: Int = 0.inv()

    fun update(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        var c = crc
        for (i in offset until offset + length) {
            val index = (c xor data[i].toInt()) and 0xFF
            c = (c ushr 8) xor TABLE[index]
        }
        crc = c
    }

    /** The CRC-32 of everything passed to [update] so far. Reading does not reset the state. */
    val value: Int
        get() = crc.inv()

    private companion object {
        val TABLE: IntArray = buildTable()

        private fun buildTable(): IntArray {
            val polynomial = 0xEDB88320.toInt()
            val table = IntArray(256)
            for (i in 0 until 256) {
                var c = i
                repeat(8) {
                    c = if (c and 1 != 0) (c ushr 1) xor polynomial else c ushr 1
                }
                table[i] = c
            }
            return table
        }
    }
}
