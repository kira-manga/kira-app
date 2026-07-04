package me.manga.yamiapk.core.cbz


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CbzManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val compressionDispatcher =
    Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    companion object {
        private const val TAG = "CbzManager"
        private const val CBZ_EXTENSION = ".cbz"
    }

    fun splitBitmapVertically(bitmap: Bitmap, maxHeight: Int = 15000): List<Bitmap> {
        val chunks = mutableListOf<Bitmap>()
        val width = bitmap.width
        val height = bitmap.height

        if (height <= maxHeight) {
            // No need to split
            return listOf(bitmap)
        }

        var currentY = 0
        while (currentY < height) {
            val chunkHeight = minOf(maxHeight, height - currentY)

            try {
                val chunk = Bitmap.createBitmap(bitmap, 0, currentY, width, chunkHeight)
                chunks.add(chunk)
                currentY += chunkHeight
            } catch (e: Exception) {
                Log.e("BitmapSplitter", "Failed to create chunk at Y=$currentY", e)
                break
            }
        }

        return chunks
    }

    /**
     * Enhanced CBZ creation with bitmap splitting for large images
     */
    suspend fun createCbzFromFilesWithSplitting(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = 75,
        maxHeight: Int = 10000, // Split images taller than this
        maxMemoryBytes: Long = 100_000_000 // ~100MB per bitmap
    ): String =withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply { mkdirs() }
        val cbzFile = File(chapterDir, "chapter_${chapterId}.cbz")

        val filesToDelete = mutableListOf<File>()
        var pageCounter = 0

        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->

            imageFiles.forEachIndexed { index, path ->
                ensureActive()
                if (index % 2 == 0) yield()

                val file = File(path)
                if (!file.exists()) {
                    Log.w("CBZ", "File not found: $path")
                    return@forEachIndexed
                }

                // Decode bitmap
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    Log.e("CBZ", "FAILED DECODE: $path")
                    return@forEachIndexed
                }

                val width = bitmap.width
                val height = bitmap.height
                val byteSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    bitmap.allocationByteCount
                } else {
                    bitmap.byteCount
                }

                Log.d("CBZ-BITMAP", "INDEX=$index | ${file.name} | WxH=${width}x${height} | bytes=$byteSize")

                // Check if bitmap needs splitting
                val needsSplitting = height > maxHeight || byteSize > maxMemoryBytes

                if (needsSplitting) {
                    Log.w("CBZ", "Image too large, splitting: $path (${width}x${height}, $byteSize bytes)")

                    val chunks = splitBitmapVertically(bitmap, maxHeight)
                    Log.d("CBZ", "Split into ${chunks.size} chunks")

                    chunks.forEachIndexed { chunkIndex, chunk ->
                        try {
                            val entryName = "page_%04d.webp".format(pageCounter)
                            zipOut.putNextEntry(ZipEntry(entryName))

                            val success = chunk.compress(webpFormat, quality, zipOut)
                            if (!success) {
                                Log.e("CBZ", "Failed to compress chunk $chunkIndex of $path")
                            } else {
                                Log.d("CBZ", "Compressed chunk $chunkIndex: $entryName")
                            }

                            zipOut.closeEntry()
                            pageCounter++

                        } catch (e: Exception) {
                            Log.e("CBZ", "FAILED COMPRESS chunk $chunkIndex: $path", e)
                        } finally {
                            chunk.recycle()
                        }
                    }

                } else {
                    // Normal compression for reasonably-sized images
                    try {
                        val entryName = "page_%04d.webp".format(pageCounter)
                        zipOut.putNextEntry(ZipEntry(entryName))

                        val success = bitmap.compress(webpFormat, quality, zipOut)
                        if (!success) {
                            Log.e("CBZ", "Failed to compress: $path")
                        }

                        zipOut.closeEntry()
                        pageCounter++

                    } catch (e: Exception) {
                        Log.e("CBZ", "FAILED COMPRESS: $path", e)
                    }
                }

                bitmap.recycle()
                filesToDelete.add(file)
            }
        }

        // Delete originals after finishing ZIP
        filesToDelete.forEach {
            if (it.delete()) {
                Log.d("CBZ", "Deleted original: ${it.name}")
            }
        }

        Log.i("CBZ", "Created CBZ with $pageCounter pages: $cbzFile")
        return@withContext cbzFile.absolutePath
    }

    /**
     * Alternative: Split based on memory size estimation
     */
    fun calculateOptimalChunkHeight(width: Int, height: Int, maxBytes: Long = 50_000_000): Int {
        val bytesPerPixel = 4 // ARGB_8888
        val bytesPerRow = width * bytesPerPixel
        val maxRows = (maxBytes / bytesPerRow).toInt()

        return minOf(maxRows, height, 10000) // Cap at 10000 for safety
    }
    suspend fun createCbzFromFiles(
        imageFiles: List<String>,
        mangaId: Long,
        chapterId: Long,
        quality: Int = 75
    ): String = withContext(Dispatchers.Default) {

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply { mkdirs() }
        val cbzFile = File(chapterDir, "chapter_${chapterId}.cbz")

        val filesToDelete = mutableListOf<File>()

        // Choose WEBP format depending on API level
        val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            // Deprecated but required for older devices
            Bitmap.CompressFormat.WEBP
        }

        ZipOutputStream(FileOutputStream(cbzFile)).use { zipOut ->

            imageFiles.forEachIndexed { index, path ->

                ensureActive()
                if (index % 2 == 0) yield()

                val file = File(path)
                if (!file.exists()) return@forEachIndexed

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                Log.e("CBZ-BITMAP", "Decode = $bitmap")

                if (bitmap == null) {
                    Log.e("CBZ", "FAILED DECODE: $path")
                    return@forEachIndexed
                }
                val width = bitmap.width
                val height = bitmap.height
                val byteSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    bitmap.allocationByteCount
                } else {
                    bitmap.byteCount
                }

                Log.e(
                    "CBZ-BITMAP",
                    "INDEX=$index | ${file.name} | WxH=${width}x${height} | bytes=$byteSize"
                )
                zipOut.putNextEntry(ZipEntry("page_%04d.webp".format(index)))
              try {
                  bitmap.compress(webpFormat, quality, zipOut)

              }catch (e:Exception){
                  Log.e("CBZException", "FAILED COMPRESS: $path   cuse ===== $e")
              }

                zipOut.closeEntry()

                bitmap.recycle()

                filesToDelete.add(file)
            }
        }

        // Delete originals after finishing ZIP
        filesToDelete.forEach { it.delete() }

        Log.e("CBZ", "Created CBZ = $cbzFile")

        return@withContext cbzFile.absolutePath
    }


    /**
     * Extract image paths from a CBZ file
     * @param cbzPath Path to the CBZ file
     * @return List of extracted image file paths
     */
    suspend fun extractImagesFromCbz(
        cbzPath: String,
        mangaId: Long,
        chapterId: Long
    ): List<String> = withContext(Dispatchers.IO) {
        val cbzFile = File(cbzPath)
        if (!cbzFile.exists()) {
            Log.e(TAG, "CBZ file does not exist: $cbzPath")
            return@withContext emptyList()
        }

        val extractDir = File(context.cacheDir, "cbz_extract/$mangaId/$chapterId")
        extractDir.mkdirs()

        // Clear any existing extracted files
        extractDir.listFiles()?.forEach { it.delete() }

        val extractedPaths = mutableListOf<String>()

        try {
            ZipFile(cbzFile).use { zipFile ->
                val entries = zipFile.entries().toList()
                    .filter { !it.isDirectory && isImageFile(it.name) }
                    .sortedBy { it.name }

                entries.forEach { entry ->
                    val outputFile = File(extractDir, entry.name)

                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    extractedPaths.add(outputFile.absolutePath)
                }
            }

            Log.i(TAG, "Extracted ${extractedPaths.size} images from CBZ")
            extractedPaths
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting CBZ file", e)
            emptyList()
        }
    }


    fun cbzExists(mangaId: Long, chapterId: Long): Boolean {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")
        return cbzFile.exists()
    }

    /**
     * Get CBZ file path if it exists
     */
    fun getCbzPath(mangaId: Long, chapterId: Long): String? {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")
        return if (cbzFile.exists()) cbzFile.absolutePath else null
    }

    /**
     * Delete a CBZ file
     */
    suspend fun deleteCbz(mangaId: Long, chapterId: Long): Boolean = withContext(Dispatchers.IO) {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")
        val cbzFile = File(chapterDir, "chapter_${chapterId}$CBZ_EXTENSION")

        if (cbzFile.exists()) {
            cbzFile.delete()
        } else {
            false
        }
    }
    /**
     * Get the number of pages in a CBZ file
     */
    suspend fun getCbzPageCount(cbzPath: String): Int = withContext(Dispatchers.IO) {
        try {
            ZipFile(File(cbzPath)).use { zipFile ->
                zipFile.entries().toList()
                    .count { !it.isDirectory && isImageFile(it.name) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting CBZ page count", e)
            0
        }
    }

    /**
     * Convert existing file-based chapter to CBZ
     */
    suspend fun convertFilesToCbz(
        mangaId: Long,
        chapterId: Long,
        existingFiles: List<String>
    ): String? = withContext(Dispatchers.Default) {
        try {
            createCbzFromFilesWithSplitting(existingFiles, mangaId, chapterId)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting files to CBZ", e)
            null
        }
    }

    private fun isImageFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    /**
     * Clean up extracted cache files
     */
    suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) = withContext(Dispatchers.IO) {
        val extractDir = File(context.cacheDir, "cbz_extract/$mangaId/$chapterId")
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
    }
}