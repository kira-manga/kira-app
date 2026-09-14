package me.manga.kira.platform.image

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.svg.SvgDecoder
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.buffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.impl.use as skiaUse

private const val SQUARE_PNG_SIZE = 8
private const val REQUESTED_PNG_WIDTH = 4
private const val SAMPLED_PNG_HEIGHT = 8
private const val SVG_WIDTH = 32
private const val SVG_HEIGHT = 24

/**
 * Raster decoding stays on the high-quality path; SVG must reach Coil's automatically discovered
 * decoder through the actual Desktop registry. No SVG factory is manually installed by these tests.
 */
@OptIn(ExperimentalCoilApi::class)
class HighQualitySkiaImageDecoderTest {

    private val options = Options(PlatformContext.INSTANCE)

    @Test
    fun decodesValidImage() {
        val result = decodeEncodedImageWithSkia(solidPng(width = SQUARE_PNG_SIZE, height = SQUARE_PNG_SIZE), options)
        val bitmap = assertIs<BitmapImage>(result.image).bitmap
        try {
            assertEquals(SQUARE_PNG_SIZE, bitmap.width)
            assertEquals(SQUARE_PNG_SIZE, bitmap.height)
        } finally {
            bitmap.close()
        }
    }

    @Test
    fun undecodableBytes_throwCleanDecodeError() =
        runTest {
            val imageLoader = newImageLoader()
            val fetchResult = sourceResult(ByteArray(64) { it.toByte() }, mimeType = null)
            try {
                val decoder = assertNotNull(imageLoader.components.newDecoder(fetchResult, options, imageLoader)).first
                assertIs<HighQualitySkiaImageDecoder>(decoder)
                assertFailsWith<IllegalStateException>("undecodable bytes surface a clean decode error") {
                    decoder.decode()
                }
            } finally {
                fetchResult.source.close()
                imageLoader.shutdown()
            }
        }

    @Test
    fun svgMimeUsesServiceLoadedDecoder() =
        runTest {
            // Leading whitespace defeats Coil's byte sniff, so this proves the exact MIME branch.
            assertSvgDecoded(svgBytes(leadingWhitespace = true), mimeType = "image/svg+xml")
        }

    @Test
    fun untypedSvgUsesServiceLoadedDecoder() =
        runTest {
            assertSvgDecoded(svgBytes(), mimeType = null)
        }

    @Test
    fun svgFactoryDeclinePreservesSource() {
        val imageLoader = newImageLoader()
        try {
            for (mimeType in listOf("image/svg+xml", null)) {
                val bytes = svgBytes(leadingWhitespace = mimeType != null)
                val fetchResult = sourceResult(bytes, mimeType)
                try {
                    assertNull(HighQualitySkiaImageDecoder.Factory().create(fetchResult, options, imageLoader))
                    assertContentEquals(bytes, fetchResult.source.source().readByteArray())
                } finally {
                    fetchResult.source.close()
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun pngRegistryKeepsHighQualityDecoderAndRequestedWidth() =
        runTest {
            val imageLoader = newImageLoader()
            val fetchResult = sourceResult(solidPng(width = 8, height = 16), mimeType = "image/png")
            val requested =
                options.copy(
                    size = Size(Dimension.Pixels(REQUESTED_PNG_WIDTH), Dimension.Undefined),
                    scale = Scale.FILL,
                )
            try {
                val decoder =
                    assertNotNull(imageLoader.components.newDecoder(fetchResult, requested, imageLoader)).first
                assertIs<HighQualitySkiaImageDecoder>(decoder)
                val result = assertNotNull(decoder.decode())
                val bitmap = assertIs<BitmapImage>(result.image).bitmap
                try {
                    assertEquals(REQUESTED_PNG_WIDTH, bitmap.width)
                    assertEquals(SAMPLED_PNG_HEIGHT, bitmap.height)
                    assertTrue(result.isSampled)
                } finally {
                    bitmap.close()
                }
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
                    assertEquals(SVG_WIDTH, bitmap.width)
                    assertEquals(SVG_HEIGHT, bitmap.height)
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
                if (includeAppDecoders) DesktopImageDecoderRegistry().registerAll().forEach { add(it) }
            }.memoryCache(null)
            .diskCache(null)
            .build()

    private fun sourceResult(
        bytes: ByteArray,
        mimeType: String?,
    ): SourceFetchResult {
        // Wrap the Buffer so closing its BufferedSource is observable (Buffer.close itself is a no-op).
        val rawSource: Source = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(rawSource.buffer(), options.fileSystem),
            mimeType = mimeType,
            dataSource = DataSource.MEMORY,
        )
    }

    /** A valid PNG of a solid colour, built with Skia so the test has no binary fixtures. */
    private fun solidPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        try {
            Canvas(bitmap).skiaUse { it.clear(Color.makeRGB(10, 120, 200)) }
            bitmap.setImmutable()
            val image = Image.makeFromBitmap(bitmap)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: error("PNG encode failed in test setup")
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            bitmap.close()
        }
    }
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
