package me.manga.kira.platform.image

import coil3.BitmapImage
import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.jetbrains.skia.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoilApi::class)
internal fun iosAvifOptions(
    size: Size,
    scale: Scale = Scale.FILL,
    precision: Precision = Precision.EXACT,
    maximum: Size = Size.ORIGINAL,
): Options =
    Options(
        context = PlatformContext.INSTANCE,
        size = size,
        scale = scale,
        precision = precision,
        extras = Extras.Builder().apply { set(Extras.Key.maxBitmapSize, maximum) }.build(),
    )

internal fun iosAvifImageLoader(afterAvif: Decoder.Factory? = null): ImageLoader =
    ImageLoader
        .Builder(PlatformContext.INSTANCE)
        .components {
            IosImageDecoderRegistry().registerAll().forEachIndexed { index, factory ->
                add(factory)
                if (index == 0 && afterAvif != null) add(afterAvif)
            }
        }.memoryCache(null)
        .diskCache(null)
        .build()

/** Exercise the real registry/decoder/ImageIO path and always dispose the resulting native bitmap. */
@OptIn(ExperimentalCoilApi::class)
internal suspend fun withIosAvifBitmap(
    bytes: ByteArray,
    options: Options,
    check: (Bitmap, DecodeResult) -> Unit,
) {
    val loader = iosAvifImageLoader()
    val source = IosAvifTestSource(Buffer().write(bytes))
    val fetch = SourceFetchResult(ImageSource(source.buffered, options.fileSystem), "image/avif", DataSource.MEMORY)
    try {
        val decoder = assertNotNull(loader.components.newDecoder(fetch, options, loader)).first
        assertIs<IosAvifDecoder>(decoder)
        val result = assertNotNull(decoder.decode())
        val bitmap = assertIs<BitmapImage>(result.image).bitmap
        try {
            assertTrue(source.closed)
            // Both fixtures are opaque; this also detects an undrawn, zero-filled output buffer.
            assertEquals(0xff, bitmap.getColor(0, 0) ushr 24)
            check(bitmap, result)
        } finally {
            bitmap.close()
        }
    } finally {
        fetch.source.close()
        loader.shutdown()
    }
}

internal class IosAvifTestSource(
    delegate: Source,
) : ForwardingSource(delegate) {
    val buffered: BufferedSource = buffer()
    var closed = false
    var afterUpstreamRead: () -> Unit = {}

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val count = super.read(sink, byteCount)
        afterUpstreamRead()
        return count
    }

    override fun close() {
        closed = true
        super.close()
    }
}
