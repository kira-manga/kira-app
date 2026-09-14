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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.impl.use as skiaUse

/** Shared iOS-used raster decoder proof on the JVM host; not native-device qualification. */
@OptIn(ExperimentalCoilApi::class)
class HighQualitySkiaJpegDecoderTest {
    private val options = Options(PlatformContext.INSTANCE)

    @Test
    fun jpegRegistryKeepsHighQualityDecoderAndRequestedWidth() =
        runTest {
            val jpeg = solidJpeg()
            val imageLoader = newImageLoader()
            val fetchResult = sourceResult(jpeg)
            try {
                val bufferedSource = fetchResult.source.source()
                val requested =
                    options.copy(
                        size = Size(Dimension.Pixels(REQUESTED_JPEG_WIDTH), Dimension.Undefined),
                        scale = Scale.FILL,
                    )
                val decoder =
                    assertNotNull(imageLoader.components.newDecoder(fetchResult, requested, imageLoader)).first
                assertIs<HighQualitySkiaImageDecoder>(decoder)
                val result = assertNotNull(decoder.decode())
                assertIs<BitmapImage>(result.image).bitmap.skiaUse { bitmap ->
                    // Check decoder ownership before our outer finally closes the ImageSource.
                    assertFailsWith<IllegalStateException> { bufferedSource.exhausted() }
                    assertEquals(REQUESTED_JPEG_WIDTH, bitmap.width)
                    assertEquals(SAMPLED_JPEG_HEIGHT, bitmap.height)
                    assertTrue(result.isSampled)
                    assertJpegPixelReadable(bitmap)
                }
            } finally {
                fetchResult.source.close()
                imageLoader.shutdown()
            }
        }

    private fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(PlatformContext.INSTANCE)
            .components {
                DesktopImageDecoderRegistry().registerAll().forEach { add(it) }
            }.memoryCache(null)
            .diskCache(null)
            .build()

    private fun sourceResult(bytes: ByteArray): SourceFetchResult {
        // Buffer.close is a no-op; wrap a Source so decoder-side closure is observable.
        val rawSource: Source = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(rawSource.buffer(), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.MEMORY,
        )
    }

    private fun solidJpeg(): ByteArray =
        Bitmap().skiaUse { bitmap ->
            bitmap.allocN32Pixels(JPEG_SOURCE_WIDTH, JPEG_SOURCE_HEIGHT)
            Canvas(bitmap).skiaUse { it.clear(Color.makeRGB(JPEG_RED, JPEG_GREEN, JPEG_BLUE)) }
            bitmap.setImmutable()
            Image.makeFromBitmap(bitmap).skiaUse { image -> encodedJpeg(image) }
        }

    private fun encodedJpeg(image: Image): ByteArray =
        assertNotNull(image.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)).skiaUse { data ->
            val jpeg = data.bytes
            val marker =
                Buffer()
                    .write(jpeg)
                    .readShort()
                    .toUShort()
                    .toInt()
            assertEquals(JPEG_SOI, marker)
            jpeg
        }

    private fun assertJpegPixelReadable(bitmap: Bitmap) {
        val pixel = bitmap.getColor(1, 1)
        assertEquals(JPEG_CHANNEL_MASK, pixel ushr JPEG_ALPHA_SHIFT)
        for (shift in listOf(JPEG_RED_SHIFT, JPEG_GREEN_SHIFT, 0)) {
            val expected = (JPEG_EXPECTED_RGB ushr shift) and JPEG_CHANNEL_MASK
            val actual = (pixel ushr shift) and JPEG_CHANNEL_MASK
            assertTrue(
                actual in (expected - JPEG_PIXEL_TOLERANCE)..(expected + JPEG_PIXEL_TOLERANCE),
                "JPEG channel at bit $shift: expected $expected ±$JPEG_PIXEL_TOLERANCE but was $actual",
            )
        }
    }
}

private const val JPEG_SOURCE_WIDTH = 8
private const val JPEG_SOURCE_HEIGHT = 16
private const val REQUESTED_JPEG_WIDTH = 4
private const val SAMPLED_JPEG_HEIGHT = 8
private const val JPEG_QUALITY = 100
private const val JPEG_SOI = 0xffd8
private const val JPEG_RED = 10
private const val JPEG_GREEN = 120
private const val JPEG_BLUE = 200
private const val JPEG_EXPECTED_RGB = 0x0a78c8
private const val JPEG_PIXEL_TOLERANCE = 4
private const val JPEG_CHANNEL_MASK = 0xff
private const val JPEG_ALPHA_SHIFT = 24
private const val JPEG_RED_SHIFT = 16
private const val JPEG_GREEN_SHIFT = 8
