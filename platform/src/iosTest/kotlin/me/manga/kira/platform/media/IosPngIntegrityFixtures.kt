package me.manga.kira.platform.media

import me.manga.kira.platform.backup.Crc32
import okio.Buffer
import okio.ByteString.Companion.decodeHex

/** Tiny generated zlib fixtures, not a production compressor/validator or recovered run payload. */
internal data class IosPngFixture(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val color: Int,
    val interlace: Int,
    private val zlibHex: String,
) {
    fun stream(): ByteArray = zlibHex.decodeHex().toByteArray()

    fun header(): ByteArray =
        Buffer()
            .writeInt(width).writeInt(height).writeByte(depth).writeByte(color)
            .writeByte(0).writeByte(0).writeByte(interlace).readByteArray()

    fun image(idats: List<ByteArray> = listOf(stream())): ByteArray {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        if (color == 3) chunks += "PLTE" to byteArrayOf(0, 0, 0, -1, -1, -1)
        chunks += idats.map { "IDAT" to it }
        chunks += "IEND" to byteArrayOf()
        return IosPngIntegrityFixtures.assemble(header(), chunks)
    }
}

internal object IosPngIntegrityFixtures {
    val valid =
        listOf(
            IosPngFixture("packed_1", 8, 9, 1, 0, 0, "78da636040070000120001"),
            IosPngFixture("packed_2", 5, 3, 2, 0, 0, "78da636080020000090001"),
            IosPngFixture("packed_4", 3, 2, 4, 0, 0, "78da636000010000060001"),
            IosPngFixture("gray_16", 2, 2, 16, 0, 0, "78da6360800100000a0001"),
            IosPngFixture("rgb_16", 2, 2, 16, 2, 0, "78da6360c00500001a0001"),
            IosPngFixture("gray_alpha_16", 2, 2, 16, 4, 0, "78da636040070000120001"),
            IosPngFixture("rgba_16", 2, 2, 16, 6, 0, "78da636020040000220001"),
            IosPngFixture("indexed_4", 3, 2, 4, 3, 0, "78da636000010000060001"),
            IosPngFixture("adam7_empty_passes", 1, 1, 1, 0, 1, "78da6360000000020001"),
            IosPngFixture("adam7_rgb_16", 3, 2, 16, 2, 1, "78da6360200e000000280001"),
            IosPngFixture("all_filters", 8, 5, 1, 0, 0, "78da6360606460626066606100000032000b"),
            IosPngFixture(
                "scratch_boundary", 1024, 33, 8, 0, 0,
                "78daedc18100000000c3a0f9539fe00655010000000000000000000000000000000000000000000000000000000000000000d70084210001",
            ),
        )

    fun special(name: String): ByteArray =
        when (name) {
            "short_rows" -> "78da636040030000110001"
            "long_rows" -> "78da6360c0000000130001"
            "bad_filter" -> "78da6365400700006c0006"
            "bad_deflate_valid_header" -> "78da07"
            "preset_dictionary" -> "782000000001"
            else -> error("Unknown fixture")
        }.decodeHex().toByteArray()

    fun assemble(
        header: ByteArray,
        chunks: List<Pair<String, ByteArray>>,
    ): ByteArray =
        Buffer()
            .apply {
                write("89504e470d0a1a0a".decodeHex())
                write(chunk("IHDR", header))
                chunks.forEach { (name, payload) -> write(chunk(name, payload)) }
            }.readByteArray()

    private fun chunk(
        name: String,
        payload: ByteArray,
    ): ByteArray {
        val type = name.encodeToByteArray()
        val crc =
            Crc32().apply {
                update(type)
                update(payload)
            }
        return Buffer().writeInt(payload.size).write(type).write(payload).writeInt(crc.value).readByteArray()
    }
}
