// 1. Update ChapterDownloadService.kt

package me.manga.yamiapk.presentation.features.download.domain

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.cbz.CbzManager
import me.manga.yamiapk.core.cbz.OptimizedCbzManager
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.domain.service.FileService
import me.manga.yamiapk.presentation.features.download.data.DownloadState
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import androidx.core.graphics.createBitmap
import me.manga.yamiapk.di.app.MainOkHttpClient
import me.manga.yamiapk.sources_repositry.ar.mangamelloplus.MangamelloPlusRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream

@Singleton
class ChapterDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    @MainOkHttpClient private val okHttpClient: OkHttpClient,
    private val fileService: FileService,
    private val notificationDao: NotificationDao,
    private val cbzManager: CbzManager,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val optimizedCbzManager: OptimizedCbzManager,
    private val dataStoreHelper: DataStoreHelper
) {

    private val DOWNLOAD_DISPATCHER = Dispatchers.IO.limitedParallelism(6)

    /**
     * Main download function - routes to streaming or batch based on API
     */
    fun downloadChapterC(
        chapter: SavedChapterEntity,
        imageUrls: List<String>,
        repo: BaseMangaRepository
    ): Flow<DownloadState> = flow {
        val api = libraryRepository.getApiById(chapter.mangaId)

        // Route to streaming download for ProManga
        if (api == MangaSource.PROCHAN.API) {
            emitAll(downloadChapterStreaming(chapter, imageUrls, repo))
        } else {
            emitAll(downloadChapterBatch(chapter, imageUrls, repo))
        }
    }.flowOn(DOWNLOAD_DISPATCHER)

    /**
     * NEW: Streaming download for ProManga
     * Downloads images as they become available
     */
// Add this new function to handle local file paths
    private suspend fun handleImageSource(
        imageSource: String,
        mangaId: Long,
        chapterId: Long,
        imageIndex: Int,
        repo: BaseMangaRepository
    ): String = withContext(Dispatchers.IO) {

        val clean = imageSource.trim().substringBefore("?")

        Log.i("CHECK_PATH", "raw='$imageSource'")
        Log.i("CHECK_PATH", "clean='$clean'")

        val local = File(clean)

        if (clean.startsWith("/data/") ||
            clean.startsWith("file://") ||
            local.exists()) {

            Log.i("CHECK_PATH", "Detected LOCAL → Copying")
            return@withContext copyLocalImage(clean, mangaId, chapterId, imageIndex)
        }

        Log.i("CHECK_PATH", "Detected URL → Downloading")
        return@withContext downloadImage(clean, mangaId, chapterId, imageIndex, repo)
    }

    // New function to copy local cached images
    private suspend fun copyLocalImage(
        sourcePath: String,
        mangaId: Long,
        chapterId: Long,
        imageIndex: Int
    ): String = withContext(Dispatchers.IO) {
        val sourceFile = File(sourcePath.removePrefix("file://"))

        if (!sourceFile.exists()) {
            throw IllegalStateException("Source file does not exist: $sourcePath")
        }

        val extension = sourceFile.extension.ifEmpty { "jpg" }

        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply {
            mkdirs()
        }

        val destFile = File(chapterDir, "image_$imageIndex.$extension")

        // Copy the file (optimized + safe for all devices)
        BufferedInputStream(sourceFile.inputStream()).use { input ->
            BufferedOutputStream(FileOutputStream(destFile)).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024) // 64 KB buffer
            }
        }


        destFile.absolutePath
    }

    /**
     * NEW: Streaming download for ProManga
     * Downloads images as they become available
     */
    private fun downloadChapterStreaming(
        chapter: SavedChapterEntity,
        imageUrls: List<String>,
        repo: BaseMangaRepository
    ): Flow<DownloadState> = flow {
        require(imageUrls.isNotEmpty()) { "No images to download" }

        val paths = mutableListOf<String>()
        val useCbz = dataStoreHelper.useCbzFormatFlow.first()
        var lastEmittedCount = 0

        try {
            Log.d("ChapterDownloadService", "Starting streaming download for ${imageUrls.size} images")

            // Download/copy images as they arrive
            imageUrls.forEachIndexed { index, imageSource ->
                currentCoroutineContext().ensureActive()

                // Only emit progress if we've added at least one more image
                if (paths.size > lastEmittedCount) {
                    emit(DownloadState.InProgress(imageUrls.size, paths.size, imageSource))
                    lastEmittedCount = paths.size
                }

                try {
                    // Use new handler that checks if it's local or remote
                    val path = handleImageSource(imageSource, chapter.mangaId, chapter.id, index, repo)
                    paths.add(path)

                    // Emit progress after each successful download/copy
                    emit(DownloadState.InProgress(imageUrls.size, paths.size, imageSource))

                    Log.d("ChapterDownloadService", "Processed image ${paths.size}/${imageUrls.size}")
                } catch (e: Exception) {
                    Log.e("ChapterDownloadService", "Failed to process image $index: ${e.message}")
                    // Continue with next image instead of failing entire chapter
                    if (e is CancellationException) throw e
                }
            }

            // Verify we got at least some images
            if (paths.isEmpty()) {
                throw IllegalStateException("Failed to download any images")
            }

            Log.d("ChapterDownloadService", "Successfully processed ${paths.size}/${imageUrls.size} images")

            // Compress if needed
            if (useCbz) {
                emit(DownloadState.Compressing(paths.size))
                Log.i("ChapterDownloadService", "Starting compression for chapter ${chapter.id}")

                try {
                    currentCoroutineContext().ensureActive()

                    val cbzPath = optimizedCbzManager.createCbzParallel(
                        paths,
                        chapter.mangaId,
                        chapter.id,
                        onProgress = { current, total ->
                            // Optional: emit compression progress
                        }
                    )
                    currentCoroutineContext().ensureActive()

                    Log.i("ChapterDownloadService", "CBZ created: $cbzPath")

                    libraryRepository.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                    notificationDao.addLocalImagePathByChapterId(chapter.id, listOf(cbzPath))

                    emit(DownloadState.Complete(listOf(cbzPath)))

                } catch (e: CancellationException) {
                    Log.w("ChapterDownloadService", "Compression cancelled for chapter ${chapter.id}")
                    paths.forEach { File(it).delete() }
                    chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.FAILED)
                    throw e

                } catch (e: Exception) {
                    Log.e("ChapterDownloadService", "Compression failed: ${e.message}", e)

                    // Fallback to original files
                    if (e is OutOfMemoryError || e.message?.contains("memory", ignoreCase = true) == true) {
                        Log.i("ChapterDownloadService", "Falling back to original files")
                        libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                        libraryRepository.markChapterAsDownloaded(chapterId = chapter.id)
                        chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.SUCCESS)
                        notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                        emit(DownloadState.Complete(paths))
                    } else {
                        paths.forEach { File(it).delete() }
                        chapterDownloadDao.updateFailure(chapter.id, "Compression failed: ${e.message}")
                        emit(DownloadState.Error(e, paths.size, imageUrls.size))
                    }
                }
            } else {
                // No compression
                libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                emit(DownloadState.Complete(paths))
            }

        } catch (e: CancellationException) {
            Log.w("ChapterDownloadService", "Streaming download cancelled for chapter ${chapter.id}")
            paths.forEach { File(it).delete() }
            chapterDownloadDao.updateFailure(chapter.id, "Download cancelled")
            throw e
        }

    }.catch { e ->
        if (e !is CancellationException) {
            Log.e("ChapterDownloadService", "Streaming download error", e)
            chapterDownloadDao.updateFailure(chapter.id, e.message)
        }
        emit(DownloadState.Error(e, 0, imageUrls.size))
    }

    // Keep your existing downloadImage function unchanged for regular URLs
    suspend fun downloadImage(
        imageUrl: String,
        mangaId: Long,
        chapterId: Long,
        imageIndex: Int,
        repo: BaseMangaRepository
    ): String = withContext(Dispatchers.IO) {
        Log.i("asfjkdaslkfajsdfasdfasdf1",imageUrl)

        val headers = Headers.Builder().apply {
            if (repo is MangamelloPlusRepository){
                if (imageUrl.contains("mangamello", ignoreCase = true) ||
                    imageUrl.contains("mello", ignoreCase = true) ||
                    imageUrl.contains("cdn.mangamello.com", ignoreCase = true)
                ) {
                    repo.imgsHeader.forEach { (name, value) -> add(name, value) }
                }
            }else {
                repo.defaultHeaders.forEach { (name, value) -> add(name, value) }
            }
        }.build()

        val request = Request.Builder()
            .headers(headers)
            .url(imageUrl)
            .build()

        okHttpClient.newCall(request).execute().use { response ->

            Log.i("asfjkdaslkfajsdfasdfasdf2",response.isSuccessful.toString())

            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download image: ${response.code}")
            }
            Log.i("asfjkdaslkfajsdfasdfasdf3",response.body .toString())

            val body = response.body ?: throw IllegalStateException("Response body is null")

            response.header("Content-Type")?.let { contentType ->
                Log.i("asfjkdaslkfajsdfasdfasdf5",contentType.toString())


            }
            val extension = detectImageExtension(response, imageUrl)

            val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply {
                mkdirs()
            }
            Log.i("asfjkdaslkfajsdfasdfasdf4",extension.toString())

            val imageFile = File(chapterDir, "image_$imageIndex.$extension")

            BufferedInputStream(body.byteStream()).use { input ->
                BufferedOutputStream(FileOutputStream(imageFile)).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }


            imageFile.absolutePath
        }
    }
    /**
     * Original batch download for other sources
     */
    private fun downloadChapterBatch(
        chapter: SavedChapterEntity,
        imageUrls: List<String>,
        repo: BaseMangaRepository
    ): Flow<DownloadState> = flow {
        require(imageUrls.isNotEmpty()) { "No images to download" }

        val total = imageUrls.size
        val paths = mutableListOf<String>()
        val useCbz = dataStoreHelper.useCbzFormatFlow.first()

        try {
            // Download all images
            for ((index, url) in imageUrls.withIndex()) {
                currentCoroutineContext().ensureActive()
                emit(DownloadState.InProgress(total, index, url))

                val path = downloadImage(url, chapter.mangaId, chapter.id, index, repo)
                paths += path
            }

            // Compress if needed
            if (useCbz) {
                emit(DownloadState.Compressing(paths.size))

                try {
                    currentCoroutineContext().ensureActive()

                    val cbzPath = optimizedCbzManager.createCbzParallel(
                        paths,
                        chapter.mangaId,
                        chapter.id
                    )
                    currentCoroutineContext().ensureActive()

                    libraryRepository.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                    notificationDao.addLocalImagePathByChapterId(chapter.id, listOf(cbzPath))

                    emit(DownloadState.Complete(listOf(cbzPath)))

                } catch (e: CancellationException) {
                    paths.forEach { File(it).delete() }
                    chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.FAILED)
                    throw e

                } catch (e: Exception) {
                    if (e is OutOfMemoryError || e.message?.contains("memory", ignoreCase = true) == true) {
                        libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                        libraryRepository.markChapterAsDownloaded(chapterId = chapter.id)
                        chapterDownloadDao.updateStateChId(chapter.id, DownloadingState.SUCCESS)
                        notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                        emit(DownloadState.Complete(paths))
                    } else {
                        paths.forEach { File(it).delete() }
                        chapterDownloadDao.updateFailure(chapter.id, "Compression failed: ${e.message}")
                        emit(DownloadState.Error(e, paths.size, total))
                    }
                }
            } else {
                libraryRepository.updateChapterLocalPaths(chapter.id, paths)
                notificationDao.addLocalImagePathByChapterId(chapter.id, paths)
                emit(DownloadState.Complete(paths))
            }

        } catch (e: CancellationException) {
            paths.forEach { File(it).delete() }
            chapterDownloadDao.updateFailure(chapter.id, "Download cancelled")
            throw e
        }

    }.catch { e ->
        if (e !is CancellationException) {
            chapterDownloadDao.updateFailure(chapter.id, e.message)
        }
        emit(DownloadState.Error(e, 0, imageUrls.size))
    }

















    // ... rest of existing functions (downloadImage, detectImageExtension, etc.)

//    suspend fun downloadImage(
//        imageUrl: String,
//        mangaId: Long,
//        chapterId: Long,
//        imageIndex: Int,
//        repo: BaseMangaRepository
//    ): String = withContext(Dispatchers.IO) {
//        val api = libraryRepository.getApiById(mangaId)
//
//        val headers = Headers.Builder().apply {
//            repo.defaultHeaders.forEach { (name, value) -> add(name, value) }
//        }.build()
//
//        val request = Request.Builder()
//            .headers(headers)
//            .url(imageUrl)
//            .build()
//
//        okHttpClient.newCall(request).execute().use { response ->
//            if (!response.isSuccessful) {
//                throw IllegalStateException("Failed to download image: ${response.code}")
//            }
//
//            val body = response.body ?: throw IllegalStateException("Response body is null")
//            val extension = detectImageExtension(response, imageUrl)
//
//            val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId").apply {
//                mkdirs()
//            }
//
//            val imageFile = File(chapterDir, "image_$imageIndex.$extension")
//
//            body.byteStream().use { input ->
//                imageFile.outputStream().use { output ->
//                    input.copyTo(output)
//                }
//            }
//
//            imageFile.absolutePath
//        }
//    }

    private fun detectImageExtension(response: Response, imageUrl: String): String {
        // 1️⃣ Always check URL extension first
        val urlExt = imageUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (urlExt in listOf("avif", "jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            return urlExt
        }

        // 2️⃣ If URL extension is weird → fallback to content-type
        val contentType = response.header("Content-Type")?.lowercase().orEmpty()

        return when {
            "avif" in contentType -> "avif"
            "jpeg" in contentType || "jpg" in contentType -> "jpg"
            "png" in contentType -> "png"
            "gif" in contentType -> "gif"
            "webp" in contentType -> "webp"
            "bmp" in contentType -> "bmp"
            else -> "jpg"
        }
    }


    fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
        fileService.deleteChapterFiles(mangaId, chapterId)
    }

    suspend fun deleteMangaFiles(mangaId: Long) {
        fileService.deleteMangaFiles(mangaId)
    }
}