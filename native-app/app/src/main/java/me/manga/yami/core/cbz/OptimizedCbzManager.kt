package me.manga.yamiapk.core.cbz

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.util.heap.detectDeviceTier
import org.aomedia.avif.android.AvifDecoder as AomAvifDecoder
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class OptimizedCbzManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OptimizedCBZ"
    }

    private val tier = detectDeviceTier(context)
    private val settings = getCbzSettings(tier)

    // Separate decoders and compressors
    private val decodeSemaphore = Semaphore(settings.maxParallelDecode)
    private val compressSemaphore = Semaphore(settings.maxParallelCompress)

    // Thread safety for AVIF native decoder
    private val avifDecoderMutex = Mutex()

    private val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }

    /**
     * Check if file is AVIF based on magic bytes
     */
    private fun isAvifFile(file: File): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(12)
                val read = stream.read(header)

                if (read < 12) return false

                // Check for AVIF signature: "ftyp" at offset 4, then "avif" or "avis"
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking AVIF magic bytes", e)
            false
        }
    }

    /**
     * Decode AVIF image using native decoder
     */
    private suspend fun decodeAvifImage(file: File): Bitmap? = avifDecoderMutex.withLock {
        try {
            val bytes = file.readBytes()

            // Validate minimum size
            if (bytes.size < 12) {
                Log.w(TAG, "AVIF file too small: ${file.name}")
                return null
            }

            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()

            // Read AVIF info
            val info = AomAvifDecoder.Info()
            if (!AomAvifDecoder.getInfo(buffer, buffer.capacity(), info)) {
                Log.w(TAG, "Invalid AVIF image: ${file.name}")
                return null
            }

            // Validate dimensions
            if (info.width <= 0 || info.height <= 0 || info.width > 8192 || info.height > 8192) {
                Log.w(TAG, "Invalid AVIF dimensions: ${info.width}x${info.height}")
                return null
            }

            Log.d(TAG, "Decoding AVIF: ${file.name} - ${info.width}x${info.height}, alpha=${info.alphaPresent}")

            // Create bitmap with proper config
            val bitmap = createBitmap(
                info.width,
                info.height,
                if (info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            )

            // Decode AVIF
            buffer.rewind()
            val success = AomAvifDecoder.decode(buffer, buffer.capacity(), bitmap, 0)

            if (!success) {
                bitmap.recycle()
                Log.w(TAG, "Failed to decode AVIF: ${file.name}")
                return null
            }

            Log.d(TAG, "Successfully decoded AVIF: ${file.name}")
            return bitmap

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "AVIF native library not available", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory decoding AVIF: ${file.name}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding AVIF: ${file.name}", e)
            return null
        } catch (e: Error) {
            Log.e(TAG, "Fatal error in AVIF decoder: ${file.name}", e)
            return null
        }
    }

    /**
     * Decode AVIF with region splitting for large images
     */
    private suspend fun decodeAvifWithRegionSplit(file: File, maxChunkHeight: Int): List<Bitmap> {
        val fullBitmap = decodeAvifImage(file) ?: return emptyList()

        return try {
            val height = fullBitmap.height
            val width = fullBitmap.width

            if (height <= maxChunkHeight) {
                return listOf(fullBitmap)
            }

            val chunks = mutableListOf<Bitmap>()
            var y = 0

            while (y < height) {
                val chunkHeight = minOf(maxChunkHeight, height - y)
                val chunk = Bitmap.createBitmap(fullBitmap, 0, y, width, chunkHeight)
                chunks.add(chunk)
                y += chunkHeight
            }

            fullBitmap.recycle()
            chunks
        } catch (e: Exception) {
            Log.e(TAG, "Error splitting AVIF bitmap", e)
            fullBitmap.recycle()
            emptyList()
        }
    }

    /**
     * Region-decoding for large standard images (height > threshold)
     */
    private fun decodeAndSplitWithRegionDecoder(
        file: File,
        maxChunkHeight: Int,
        quality: Int
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight

        file.inputStream().use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }

            decoder?.let { dec ->
                var y = 0

                while (y < height) {
                    val chunkHeight = minOf(maxChunkHeight, height - y)
                    val region = Rect(0, y, width, y + chunkHeight)

                    val bitmap = dec.decodeRegion(region, BitmapFactory.Options())
                    if (bitmap != null) {
                        val bytes = compressBitmap(bitmap, quality)
                        chunks.add(bytes)
                        bitmap.recycle()
                    }

                    y += chunkHeight
                }

                dec.recycle()
            }
        }

        return chunks
    }

    private fun decodeWithSampling(file: File, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        val sampleSize = calculateInSampleSize(
            options.outWidth,
            options.outHeight,
            maxDimension
        )

        options.inSampleSize = sampleSize
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var size = 1
        while (width / size > maxDim || height / size > maxDim) {
            size *= 2
        }
        return size
    }

    private fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(webpFormat, quality, output)
        return output.toByteArray()
    }

    /**
     * MAIN PARALLEL CBZ FUNCTION
     */
    suspend fun createCbzParallel(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        onProgress: ((Int, Int) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        chapterDir.mkdirs()

        val outputFile = File(chapterDir, "chapter_${chapterId}.cbz")

        try {
            val processed = coroutineScope {
                imageFiles.mapIndexed { index, path ->
                    async {
                        ensureActive() // Check for cancellation

                        val decoded = decodeSemaphore.withPermit {
                            ensureActive() // Check again before decoding
                            decodeImageSafely(File(path))
                        }

                        val compressed = compressSemaphore.withPermit {
                            ensureActive() // Check again before compressing
                            compressChunks(decoded)
                        }

                        onProgress?.invoke(index + 1, imageFiles.size)
                        compressed
                    }
                }.awaitAll()
            }

            // Check for cancellation before starting ZIP operation
            ensureActive()

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024)).use { zip ->
                var index = 0
                processed.forEach { chunkList ->
                    ensureActive() // Check for cancellation during zipping
                    chunkList.forEach { bytes ->
                        zip.putNextEntry(ZipEntry("page_%04d.webp".format(index)))
                        zip.write(bytes)
                        zip.closeEntry()
                        index++
                    }
                }
            }

            // Final cancellation check before cleanup
            ensureActive()

            // Delete originals only on success
            imageFiles.forEach { File(it).delete() }

            outputFile.absolutePath

        } catch (e: CancellationException) {
            Log.w(TAG, "CBZ creation cancelled for chapter $chapterId")

            // Clean up partial CBZ file if it was created
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                    Log.d(TAG, "Deleted partial CBZ: ${outputFile.absolutePath}")
                } catch (deleteException: Exception) {
                    Log.e(TAG, "Failed to delete partial CBZ", deleteException)
                }
            }

            // Re-throw to propagate cancellation
            throw e

        } catch (e: Exception) {
            // Clean up partial CBZ file on any other error
            if (outputFile.exists()) {
                outputFile.delete()
            }

            Log.e(TAG, "Error creating CBZ: ${e.message}", e)
            throw e
        }
    }

    /**
     * Decode logic based on device tier - now with AVIF support
     */
    private suspend fun decodeImageSafely(file: File): List<Bitmap> {
        // Check if file is AVIF
        if (isAvifFile(file)) {
            return decodeAvifImageSafely(file)
        }

        // Standard image decoding
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        val width = opts.outWidth
        val height = opts.outHeight
        val estimatedBytes = width * height * 4L

        Log.d(TAG, "Load ${file.name}: ${width}x${height}, ~${estimatedBytes / 1_000_000}MB")

        return when {
            height > settings.regionDecodeThreshold ->
                decodeAndSplitBitmaps(file, settings.regionDecodeThreshold)

            estimatedBytes > settings.samplingThreshold ->
                listOfNotNull(decodeWithSampling(file, settings.regionDecodeThreshold))

            else ->
                listOfNotNull(BitmapFactory.decodeFile(file.absolutePath))
        }
    }

    /**
     * Decode AVIF image safely with tier-based logic
     */
    private suspend fun decodeAvifImageSafely(file: File): List<Bitmap> {
        return try {
            // First decode to get dimensions
            val testBitmap = decodeAvifImage(file)

            if (testBitmap == null) {
                Log.w(TAG, "Failed to decode AVIF: ${file.name}")
                return emptyList()
            }

            val width = testBitmap.width
            val height = testBitmap.height
            val estimatedBytes = width * height * 4L

            Log.d(TAG, "AVIF ${file.name}: ${width}x${height}, ~${estimatedBytes / 1_000_000}MB")

            // If image is small enough, use the test bitmap
            if (height <= settings.regionDecodeThreshold && estimatedBytes <= settings.samplingThreshold) {
                return listOf(testBitmap)
            }

            // Recycle test bitmap and decode with region split for large images
            testBitmap.recycle()

            if (height > settings.regionDecodeThreshold) {
                decodeAvifWithRegionSplit(file, settings.regionDecodeThreshold)
            } else {
                // Decode normally for medium-sized images
                listOfNotNull(decodeAvifImage(file))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding AVIF safely: ${file.name}", e)
            emptyList()
        }
    }

    private fun decodeAndSplitBitmaps(file: File, maxChunkHeight: Int): List<Bitmap> {
        val result = mutableListOf<Bitmap>()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight

        file.inputStream().use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }

            decoder?.let { dec ->
                var y = 0
                while (y < height) {
                    val h = minOf(maxChunkHeight, height - y)
                    val region = Rect(0, y, width, y + h)
                    dec.decodeRegion(region, BitmapFactory.Options())?.let {
                        result.add(it)
                    }
                    y += h
                }
                dec.recycle()
            }
        }

        return result
    }

    private fun compressChunks(bitmaps: List<Bitmap>): List<ByteArray> =
        bitmaps.map { bmp ->

            val arr = compressBitmap(bmp, settings.webpQuality)
            bmp.recycle()
            arr
        }
}