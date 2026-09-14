package me.manga.kira.platform.image

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.svg.SvgDecoder
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.buffer
import org.jetbrains.skia.Color
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Exercises Native SVG service discovery and decoding without replacing the production registry. */
@OptIn(ExperimentalCoilApi::class)
class IosSvgDecoderRoutingTest {
    private val options = Options(PlatformContext.INSTANCE)

    @Test
    fun svgMimeUsesServiceLoadedDecoder() =
        runTest {
            // Leading whitespace defeats the sniff, so this specifically exercises MIME routing.
            assertSvgDecoded(svgBytes(leadingWhitespace = true), mimeType = "image/svg+xml")
        }

    @Test
    fun untypedSvgUsesServiceLoadedDecoder() =
        runTest {
            assertSvgDecoded(svgBytes(), mimeType = null)
        }

    @Test
    fun avifFactoryKeepsFirstClaimWithoutConsumingSource() {
        val imageLoader = newImageLoader()
        val bytes =
            Buffer()
                .writeInt(24)
                .writeUtf8("ftypavif")
                .writeInt(0)
                .writeUtf8("avifmif1")
                .readByteArray()
        // Even a misleading SVG MIME must not outrank the iOS AVIF byte-sniff factory.
        val fetchResult = sourceResult(bytes, mimeType = "image/svg+xml")
        try {
            val selected = assertNotNull(imageLoader.components.newDecoder(fetchResult, options, imageLoader))
            assertIs<IosAvifDecoder>(selected.first)
            assertEquals(0, selected.second)
            assertContentEquals(bytes, fetchResult.source.source().readByteArray())
            // This is only an ftyp header, not a decodable AVIF. Do not call decode().
        } finally {
            fetchResult.source.close()
            imageLoader.shutdown()
        }
    }

    private suspend fun assertSvgDecoded(
        bytes: ByteArray,
        mimeType: String?,
    ) {
        for (includeAppDecoders in listOf(false, true)) {
            val imageLoader = newImageLoader(includeAppDecoders)
            val fetchResult = sourceResult(bytes, mimeType)
            try {
                val decoder = assertNotNull(imageLoader.components.newDecoder(fetchResult, options, imageLoader)).first
                assertIs<SvgDecoder>(decoder, "includeAppDecoders=$includeAppDecoders")
                val result = assertNotNull(decoder.decode())
                val bitmap = assertIs<BitmapImage>(result.image).bitmap
                try {
                    assertEquals(32, bitmap.width)
                    assertEquals(24, bitmap.height)
                    assertEquals(Color.makeRGB(10, 120, 200), bitmap.getColor(1, 1))
                } finally {
                    bitmap.close()
                }
            } finally {
                fetchResult.source.close()
                imageLoader.shutdown()
            }
        }
    }

    private fun newImageLoader(includeAppDecoders: Boolean = true): ImageLoader =
        ImageLoader
            .Builder(PlatformContext.INSTANCE)
            .components {
                if (includeAppDecoders) IosImageDecoderRegistry().registerAll().forEach { add(it) }
            }.memoryCache(null)
            .diskCache(null)
            .build()

    private fun sourceResult(
        bytes: ByteArray,
        mimeType: String?,
    ): SourceFetchResult {
        val rawSource: Source = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(rawSource.buffer(), options.fileSystem),
            mimeType = mimeType,
            dataSource = DataSource.MEMORY,
        )
    }

    private fun svgBytes(leadingWhitespace: Boolean = false): ByteArray {
        val svg =
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="32" height="24">
              <rect width="32" height="24" fill="#0a78c8"/>
            </svg>
            """.trimIndent()
        return ((if (leadingWhitespace) "\n" else "") + svg).encodeToByteArray()
    }
}
