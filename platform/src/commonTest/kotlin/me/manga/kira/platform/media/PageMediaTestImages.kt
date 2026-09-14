package me.manga.kira.platform.media

import me.manga.kira.platform.backup.Crc32
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

/** Small synthetic fixtures; their real decoder results are asserted in native-target tests. */
internal object PageMediaTestImages {
    fun png(): ByteArray =
        requireNotNull(
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5CYII="
                .decodeBase64(),
        ).toByteArray()

    fun gif(): ByteArray =
        (
            "47494638396101000100800000000000ffffff" +
                "21f90401000000002c00000000010001000002024401003b"
        ).decodeHex().toByteArray()

    fun bmp(): ByteArray =
        Buffer()
            .apply {
                writeUtf8("BM")
                writeIntLe(58)
                writeIntLe(0)
                writeIntLe(54)
                writeIntLe(40)
                writeIntLe(1)
                writeIntLe(1)
                writeShortLe(1)
                writeShortLe(24)
                writeIntLe(0)
                writeIntLe(4)
                repeat(4) { writeIntLe(0) }
                writeIntLe(0)
            }.readByteArray()

    fun html(): ByteArray = "<html><title>Just a moment</title>challenge</html>".encodeToByteArray()

    fun badPngCrc(): ByteArray = changePngChunk("IDAT", updateCrc = false) { it[0] = (it[0].toInt() xor 0xff).toByte() }

    /** Structurally complete PNG with valid chunk CRCs but invalid zlib pixels. Native decode must fail. */
    fun corruptPngPixels(): ByteArray = changePngChunk("IDAT", updateCrc = true) { it.fill(0) }

    fun pngDimensions(
        width: Int,
        height: Int,
    ): ByteArray =
        changePngChunk("IHDR", updateCrc = true) {
            Buffer()
                .writeInt(width)
                .writeInt(height)
                .readByteArray()
                .copyInto(it)
        }

    private fun changePngChunk(
        name: String,
        updateCrc: Boolean,
        change: (ByteArray) -> Unit,
    ): ByteArray {
        val input = Buffer().write(png())
        val output = Buffer().write(input.readByteArray(8))
        while (!input.exhausted()) {
            val count = input.readInt()
            val type = input.readByteArray(4)
            val payload = input.readByteArray(count.toLong())
            var crc = input.readInt()
            if (type.decodeToString() == name) {
                change(payload)
                if (updateCrc) crc = pngChunkCrc(type, payload)
            }
            output
                .writeInt(count)
                .write(type)
                .write(payload)
                .writeInt(crc)
        }
        return output.readByteArray()
    }

    private fun pngChunkCrc(
        type: ByteArray,
        payload: ByteArray,
    ): Int {
        val crc = Crc32()
        crc.update(type)
        crc.update(payload)
        return crc.value
    }
}
