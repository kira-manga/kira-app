    package me.manga.yamiapk.core.avif

    import android.graphics.Bitmap
    import android.util.Log
    import androidx.core.graphics.createBitmap
    import coil3.asImage
    import coil3.decode.DecodeResult
    import coil3.decode.Decoder
    import coil3.fetch.SourceFetchResult
    import coil3.request.Options
    import kotlinx.coroutines.sync.Mutex
    import kotlinx.coroutines.sync.withLock
    import okio.BufferedSource
    import org.aomedia.avif.android.AvifDecoder as AomAvifDecoder
    import java.nio.ByteBuffer

    class AvifDecoderCoil(
        private val source: BufferedSource,
        private val options: Options
    ) : Decoder {

        companion object {
            private const val TAG = "AvifDecoderCoil"
            // Thread safety for the native decoder
            private val decoderMutex = Mutex()
        }

        override suspend fun decode(): DecodeResult? = decoderMutex.withLock {
            var bitmap: Bitmap? = null

            try {
                // Read all bytes from source
                val bytes = source.use { it.readByteArray() }

                // Validate minimum size
                if (bytes.size < 12) {
                    Log.w(TAG, "File too small to be a valid AVIF image")
                    return null // Return null to let Coil try other decoders
                }

                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.rewind()

                // Read AVIF info
                val info = AomAvifDecoder.Info()
                if (!AomAvifDecoder.getInfo(buffer, buffer.capacity(), info)) {
                    Log.w(TAG, "Invalid AVIF image: getInfo failed")
                    return null // Return null instead of throwing
                }

                val maxAspectRatio = 10f // manga pages are tall, but not THIS tall
                val ratio = info.height.toFloat() / info.width.toFloat()

                if (ratio > maxAspectRatio) {
                    Log.w(TAG, "Rejected AVIF due to insane aspect ratio: ${info.width}x${info.height}")
                    return null
                }

                Log.d(TAG, "Decoding AVIF: ${info.width}x${info.height}, alpha=${info.alphaPresent}, depth=${info.depth}")

                // Create bitmap with proper config
                bitmap = createBitmap(
                    info.width,
                    info.height,
                    if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                )

                // Decode AVIF
                buffer.rewind()
                val ok = AomAvifDecoder.decode(buffer, buffer.capacity(), bitmap, 0)

                if (!ok) {
                    bitmap.recycle()
                    Log.w(TAG, "Failed to decode AVIF: decode returned false")
                    return null
                }

                Log.d(TAG, "Successfully decoded AVIF image")
                return DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = false
                )
            } catch (e: UnsatisfiedLinkError) {
                // Native library not loaded properly
                bitmap?.recycle()
                Log.e(TAG, "Native library error - AVIF decoder unavailable", e)
                return null
            } catch (e: OutOfMemoryError) {
                // Out of memory while creating bitmap
                bitmap?.recycle()
                Log.e(TAG, "Out of memory while decoding AVIF", e)
                return null
            } catch (e: Exception) {
                // Any other error
                bitmap?.recycle()
                Log.e(TAG, "Error decoding AVIF image, will try other decoders", e)
                return null
            } catch (e: Error) {
                // Catch native crashes (SIGSEGV, etc.)
                bitmap?.recycle()
                Log.e(TAG, "Fatal error in native AVIF decoder", e)
                return null
            }
        }

        class Factory : Decoder.Factory {

            override fun create(
                result: SourceFetchResult,
                options: Options,
                imageLoader: coil3.ImageLoader
            ): Decoder? {
                return try {
                    // Quick MIME check
                    val mime = result.mimeType?.lowercase()
                    if (mime == "image/avif") {
                        return AvifDecoderCoil(result.source.source(), options)
                    }

                    // Check magic bytes safely
                    val peekSource = result.source.source().peek()
                    if (peekSource.request(12)) {
                        val header = peekSource.readByteArray(12)

                        // Check for AVIF signature
                        val isAvif = header.size >= 12 &&
                                header[4] == 'f'.code.toByte() &&
                                header[5] == 't'.code.toByte() &&
                                header[6] == 'y'.code.toByte() &&
                                header[7] == 'p'.code.toByte() &&
                                (
                                        (header[8] == 'a'.code.toByte() &&
                                                header[9] == 'v'.code.toByte() &&
                                                header[10] == 'i'.code.toByte() &&
                                                header[11] == 'f'.code.toByte()) ||
                                                (header[8] == 'a'.code.toByte() &&
                                                        header[9] == 'v'.code.toByte() &&
                                                        header[10] == 'i'.code.toByte() &&
                                                        header[11] == 's'.code.toByte())
                                        )

                        if (isAvif) {
                            AvifDecoderCoil(result.source.source(), options)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e("AvifDecoderCoil", "Error in decoder factory", e)
                    null
                }
            }
        }
    }