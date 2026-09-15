package me.manga.kira.platform.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tiny real-ImageIO cases and allocation-free geometry checks, not a device RSS guarantee. */
class IosPngIntegrityTest {
    private val inspector = IosPageMediaInspector()

    @Test
    fun packed16BitAndAdam7LayoutsRequireRealImageIoValidation() {
        IosPngIntegrityFixtures.valid.forEach { assertValid(it, it.image(), it.name) }
        val palette = "PLTE" to byteArrayOf(0, 0, 0, -1, -1, -1)
        val transparent =
            listOf(
                fixture("packed_1") to listOf("tRNS" to byteArrayOf(0, 0)),
                fixture("rgb_16") to listOf(palette, "tRNS" to ByteArray(6)),
                fixture("indexed_4") to listOf(palette, "tRNS" to byteArrayOf(0, -1)),
            )
        transparent.forEach { (fixture, prefix) ->
            val image =
                IosPngIntegrityFixtures.assemble(
                    fixture.header(),
                    prefix + listOf("IDAT" to fixture.stream(), "IEND" to byteArrayOf()),
                )
            assertValid(fixture, image, "${fixture.name}_transparency")
        }
    }

    @Test
    fun splitAndEmptyIdatChunksKeepOneContinuousStream() {
        listOf(fixture("packed_1"), fixture("scratch_boundary")).forEach { fixture ->
            // Every compressed byte is a separate IDAT, including the zlib header and Adler footer.
            val idats = mutableListOf(byteArrayOf())
            fixture.stream().forEach { byte ->
                idats += byteArrayOf(byte)
                idats += byteArrayOf()
            }
            assertValid(fixture, fixture.image(idats), "${fixture.name}_split_with_empty_idats")
        }
    }

    @Test
    fun corruptStreamsRowsAndChunkOrderingNeverValidate() {
        val gray = fixture("packed_1")
        val rgb = fixture("rgb_16")
        val indexed = fixture("indexed_4")
        val rgba = fixture("rgba_16")
        val stream = gray.stream()
        val idat = "IDAT" to stream
        val end = "IEND" to byteArrayOf()
        val text = "tEXt" to byteArrayOf(107, 0, 118)
        val palette = "PLTE" to byteArrayOf(0, 0, 0, -1, -1, -1)
        fun chunks(fixture: IosPngFixture, vararg chunks: Pair<String, ByteArray>): ByteArray =
            IosPngIntegrityFixtures.assemble(fixture.header(), chunks.toList())

        val invalid =
            listOf("short_rows", "long_rows", "bad_filter", "bad_deflate_valid_header", "preset_dictionary")
                .map { name -> name to gray.image(listOf(IosPngIntegrityFixtures.special(name))) }
                .toMutableList()
        invalid +=
            listOf(
                "wrong_adler" to gray.image(listOf(stream.copyOf().apply { this[lastIndex] = 0 })),
                "truncated_zlib" to gray.image(listOf(stream.copyOf(stream.size - 2))),
                "header_only_zlib" to gray.image(listOf(stream.copyOf(2))),
                "zero_zlib_header" to gray.image(listOf(ByteArray(stream.size))),
                "extra_compressed_byte" to gray.image(listOf(stream + byteArrayOf(0))),
                "concatenated_zlib_streams" to gray.image(listOf(stream + stream)),
                "nonempty_idat_after_end" to gray.image(listOf(stream, byteArrayOf(), byteArrayOf(0))),
                "missing_iend" to chunks(gray, idat),
                "bad_iend_crc" to gray.image().apply { this[lastIndex] = 0 },
                "trailing_after_iend" to (gray.image() + byteArrayOf(0)),
                "nonempty_iend" to chunks(gray, idat, "IEND" to byteArrayOf(0)),
                "duplicate_ihdr" to chunks(gray, "IHDR" to gray.header(), idat, end),
                "separated_idat_run" to chunks(
                    gray, "IDAT" to stream.copyOf(2), text, "IDAT" to stream.copyOfRange(2, stream.size), end,
                ),
                "separated_empty_idat" to chunks(gray, idat, text, "IDAT" to byteArrayOf(), end),
                "unknown_critical_chunk" to chunks(gray, "ABCD" to byteArrayOf(), idat, end),
                "reserved_chunk_name_bit" to chunks(gray, "abcD" to byteArrayOf(), idat, end),
                "nonletter_chunk_name" to chunks(gray, "a1CD" to byteArrayOf(), idat, end),
                "palette_on_grayscale" to chunks(gray, palette, idat, end),
                "duplicate_palette" to chunks(rgb, palette, palette, "IDAT" to rgb.stream(), end),
                "bad_palette_length" to chunks(rgb, "PLTE" to ByteArray(4), "IDAT" to rgb.stream(), end),
                "palette_after_idat" to chunks(rgb, "IDAT" to rgb.stream(), palette, end),
                "indexed_without_palette" to chunks(indexed, "IDAT" to indexed.stream(), end),
                "palette_exceeds_index_depth" to chunks(
                    indexed, "PLTE" to ByteArray(51), "IDAT" to indexed.stream(), end,
                ),
                "bad_gray_transparency_length" to chunks(gray, "tRNS" to byteArrayOf(0), idat, end),
                "duplicate_transparency" to chunks(gray, "tRNS" to ByteArray(2), "tRNS" to ByteArray(2), idat, end),
                "transparency_after_idat" to chunks(gray, idat, "tRNS" to ByteArray(2), end),
                "palette_after_transparency" to chunks(
                    rgb, "tRNS" to ByteArray(6), palette, "IDAT" to rgb.stream(), end,
                ),
                "indexed_transparency_without_palette" to chunks(
                    indexed, "tRNS" to byteArrayOf(0), "IDAT" to indexed.stream(), end,
                ),
                "indexed_transparency_exceeds_palette" to chunks(
                    indexed, palette, "tRNS" to ByteArray(3), "IDAT" to indexed.stream(), end,
                ),
                "transparency_on_alpha" to chunks(rgba, "tRNS" to ByteArray(2), "IDAT" to rgba.stream(), end),
                "wrong_compression_method" to IosPngIntegrityFixtures.assemble(
                    gray.header().apply { this[10] = 1 }, listOf(idat, end),
                ),
                "wrong_filter_method" to IosPngIntegrityFixtures.assemble(
                    gray.header().apply { this[11] = 1 }, listOf(idat, end),
                ),
            )
        invalid.forEach { (name, bytes) -> assertIs<PageInspection.Invalid>(inspector.inspect(bytes), name) }
    }

    @Test
    fun filteredWorkAndGeometryAreCheckedWithoutExpandedAllocation() {
        val exact = assertNotNull(iosPngLayout(1, 536_870_912, 8, 0, 0))
        assertEquals(IOS_PNG_MAX_FILTERED_BYTES, exact.filteredBytes)
        assertNull(iosPngLayout(1, 536_870_913, 8, 0, 0), "one row above filtered work limit")
        assertNull(iosPngLayout(Int.MAX_VALUE, Int.MAX_VALUE, 16, 6, 0), "checked Int32 row product")
        assertNull(iosPngLayout(Int.MAX_VALUE, Int.MAX_VALUE, 16, 6, 1), "checked Int32 Adam7 products")
        assertEquals(9L, assertNotNull(iosPngLayout(5, 3, 2, 0, 0)).filteredBytes)
        assertEquals(
            listOf(IosPngRowPass(1, 1)),
            assertNotNull(iosPngLayout(1, 1, 1, 0, 1)).passes,
            "Adam7 empty passes contain no filter byte",
        )
        assertEquals(40L, assertNotNull(iosPngLayout(3, 2, 16, 2, 1)).filteredBytes)
        assertEquals(
            listOf(2L, 2L, 1L, 3L, 2L, 5L, 4L).map { IosPngRowPass(1, it) },
            assertNotNull(iosPngLayout(8, 9, 1, 0, 1)).passes,
            "all seven Adam7 passes use their own geometry",
        )
        val invalid =
            listOf(
                "zero_width" to intArrayOf(0, 1, 8, 0, 0),
                "negative_height" to intArrayOf(1, -1, 8, 0, 0),
                "illegal_gray_depth" to intArrayOf(1, 1, 3, 0, 0),
                "illegal_rgb_depth" to intArrayOf(1, 1, 2, 2, 0),
                "illegal_indexed_depth" to intArrayOf(1, 1, 16, 3, 0),
                "illegal_color_type" to intArrayOf(1, 1, 8, 1, 0),
                "illegal_interlace" to intArrayOf(1, 1, 8, 0, 2),
            )
        invalid.forEach { (name, values) ->
            assertFailsWith<PageFramingException>(name) {
                iosPngLayout(values[0], values[1], values[2], values[3], values[4])
            }
        }
    }

    private fun fixture(name: String): IosPngFixture = IosPngIntegrityFixtures.valid.single { it.name == name }

    private fun assertValid(fixture: IosPngFixture, bytes: ByteArray, name: String) {
        val metadata = assertIs<PageInspection.Valid>(inspector.inspect(bytes), name).metadata
        assertEquals(PageImageMetadata(PageImageFormat.PNG, fixture.width, fixture.height), metadata, name)
    }
}
