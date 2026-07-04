package me.manga.yamiapk.sources_repositry.ar.promanga.models.imgs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.util.heap.DeviceTier
import me.manga.yamiapk.core.util.heap.detectDeviceTier
import me.manga.yamiapk.sources_repositry.ar.promanga.models.ImageCombinerState
import java.io.File
import kotlin.math.min

private const val TAG = "ProMangaImageCombiner"
private const val MAX_CANVAS_DIMENSION = 4096

class ProMangaImageCombiner(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val headers: Map<String, String>,
    private val cdnPath: String,
    private val applicationScope: CoroutineScope

) {

    companion object {
        private const val MAX_CACHE_SIZE_MB = 100L
        private const val MAX_CACHE_AGE_DAYS = 2L
    }

    init {
        // Run cache cleanup in background coroutine (non-blocking)
        applicationScope.launch {
            cleanOldCacheFilesInBackground()
        }
    }

    private val cdnBase = "https://$cdnPath.prochan.net"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Detect device tier once on initialization
    private val deviceTier: DeviceTier by lazy { detectDeviceTier(context) }

    // Configure parallel loading based on device tier
    private val parallelLoadCount: Int = when (deviceTier) {
        DeviceTier.LOW_END -> 2      // Load 2 pieces at a time
        DeviceTier.MID_RANGE -> 4    // Load 4 pieces at a time
        DeviceTier.HIGH_END -> 8     // Load 8 pieces at a time
    }

    // Memory cache size based on device
    private val memoryCacheSize: Int = when (deviceTier) {
        DeviceTier.LOW_END -> 3
        DeviceTier.MID_RANGE -> 4
        DeviceTier.HIGH_END -> 6
    }

    fun combineChapterImagesStreaming(
        maps: List<ImageMapMetadata>,
        singleImages: List<String>
    ): Flow<ImageCombinerState> = flow {
        Log.d(TAG, "Streaming on ${deviceTier.name} device: ${singleImages.size} single images, ${maps.size} maps")

        var totalEmitted = 0

        // Emit single images first
        singleImages.forEach { imageUrl ->
            emit(ImageCombinerState.SingleImageReady(
                imageUrl = "$cdnBase$imageUrl",
                currentIndex = totalEmitted,
                totalImages = singleImages.size + maps.size
            ))
            totalEmitted++
        }

        // Process each map and emit immediately after combining
        maps.forEachIndexed { index, mapData ->
            Log.d(TAG, "Processing Map[$index/${maps.size}]: mode=${mapData.mode}")

            try {
                val combined = combinePiecesOptimized(mapData)
                if (combined != null) {
                    val cachePath = saveBitmapToCache(combined, index)
                    Log.d(TAG, "✔ Map[$index] saved: $cachePath")
                    combined.recycle()

                    // Emit only this newly combined image
                    emit(ImageCombinerState.SingleImageReady(
                        imageUrl = cachePath,
                        currentIndex = totalEmitted,
                        totalImages = singleImages.size + maps.size
                    ))
                    totalEmitted++

                } else {
                    Log.e(TAG, "✘ Map[$index] failed, using fallback")
                    mapData.pieces.firstOrNull()?.let { fallback ->
                        emit(ImageCombinerState.SingleImageReady(
                            imageUrl = "$cdnBase$fallback",
                            currentIndex = totalEmitted,
                            totalImages = singleImages.size + maps.size
                        ))
                        totalEmitted++
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM on map[$index]", e)
                Runtime.getRuntime().gc()

                mapData.pieces.firstOrNull()?.let { fallback ->
                    emit(ImageCombinerState.SingleImageReady(
                        imageUrl = "$cdnBase$fallback",
                        currentIndex = totalEmitted,
                        totalImages = singleImages.size + maps.size
                    ))
                    totalEmitted++
                }

                emit(ImageCombinerState.Error(
                    message = "Out of memory on map $index",
                    imagesEmittedSoFar = totalEmitted
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error on map[$index]", e)

                mapData.pieces.firstOrNull()?.let { fallback ->
                    emit(ImageCombinerState.SingleImageReady(
                        imageUrl = "$cdnBase$fallback",
                        currentIndex = totalEmitted,
                        totalImages = singleImages.size + maps.size
                    ))
                    totalEmitted++
                }

                emit(ImageCombinerState.Error(
                    message = "Error on map $index: ${e.message}",
                    imagesEmittedSoFar = totalEmitted
                ))
            }
        }

        // Emit final complete state
        emit(ImageCombinerState.Complete(totalImagesEmitted = totalEmitted))
    }


    private fun addFallback(result: MutableList<String>, mapData: ImageMapMetadata) {
        mapData.pieces.firstOrNull()?.let {
            result.add("$cdnBase$it")
        }
    }

    private suspend fun combinePiecesOptimized(mapData: ImageMapMetadata): Bitmap? {
        var targetWidth = mapData.dim[0]
        var targetHeight = mapData.dim[1]

        // Calculate safe dimensions
        if (targetWidth > MAX_CANVAS_DIMENSION || targetHeight > MAX_CANVAS_DIMENSION) {
            val scale = min(
                MAX_CANVAS_DIMENSION.toFloat() / targetWidth,
                MAX_CANVAS_DIMENSION.toFloat() / targetHeight
            )
            targetWidth = (targetWidth * scale).toInt()
            targetHeight = (targetHeight * scale).toInt()
            Log.w(TAG, "Canvas downsized to ${targetWidth}x${targetHeight}")
        }

        // Calculate expected piece dimensions
        val pieceSize = calculatePieceSize(mapData.mode, targetWidth, targetHeight, mapData.pieces.size)

        Log.d(TAG, "Loading pieces with estimated size: ${pieceSize.width}x${pieceSize.height} (parallel: $parallelLoadCount)")

        // Load pieces with parallel loading based on device tier
        val orderedBitmaps = loadPiecesInOrderParallel(mapData, pieceSize)

        if (orderedBitmaps.isEmpty()) {
            Log.e(TAG, "No bitmaps loaded")
            return null
        }

        // Create canvas
        val finalBitmap = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            orderedBitmaps.forEach { it.recycle() }
            throw e
        }

        val canvas = Canvas(finalBitmap)

        try {
            when (mapData.mode) {
                "grid_2x1", "vertical_5", "vertical_2", "vertical_3", "vertical_4", "grid_3x1" -> {
                    drawHorizontal(canvas, orderedBitmaps, targetWidth, targetHeight)
                }
                "grid_1x2","grid_1x3" -> {
                    drawVertical(canvas, orderedBitmaps, targetWidth, targetHeight)
                }
                "grid_2x2" -> {
                    drawGrid(canvas, orderedBitmaps, targetWidth, targetHeight, 2, 2)
                }
                "grid_2x3" -> {
                    drawGrid(canvas, orderedBitmaps, targetWidth, targetHeight, 2, 3)
                }
                "grid_3x2" -> {
                    drawGrid(canvas, orderedBitmaps, targetWidth, targetHeight, 3, 2)
                }
                else -> {
                    Log.w(TAG, "Unknown mode: ${mapData.mode}")
                    drawVertical(canvas, orderedBitmaps, targetWidth, targetHeight)
                }
            }
        } finally {
            orderedBitmaps.forEach { it.recycle() }
        }

        return finalBitmap
    }

    private data class PieceSize(val width: Int, val height: Int)

    private fun calculatePieceSize(
        mode: String,
        targetW: Int,
        targetH: Int,
        pieceCount: Int
    ): PieceSize {
        return when {
            mode.startsWith("vertical_") -> {
                PieceSize(targetW, targetH / pieceCount)
            }
            mode.startsWith("grid_1x") -> {
                PieceSize(targetW / pieceCount, targetH)
            }
            mode == "grid_2x1" || mode == "grid_3x1" -> {
                val rows = mode.substringBefore("x").last().digitToInt()
                PieceSize(targetW, targetH / rows)
            }
            mode == "grid_2x2" -> {
                PieceSize(targetW / 2, targetH / 2)
            }
            mode == "grid_2x3" -> {
                PieceSize(targetW / 2, targetH / 3)
            }
            mode == "grid_3x2" -> {
                PieceSize(targetW / 3, targetH / 2)
            }
            else -> {
                PieceSize(targetW, targetH / pieceCount.coerceAtLeast(1))
            }
        }
    }

    /**
     * Load pieces in parallel with device-tier-based chunking
     * LOW_END: 2 pieces at a time
     * MID_RANGE: 4 pieces at a time
     * HIGH_END: 8 pieces at a time
     */
    private suspend fun loadPiecesInOrderParallel(
        mapData: ImageMapMetadata,
        estimatedSize: PieceSize
    ): List<Bitmap> = coroutineScope {
        val pieces = mapData.pieces
        val order = mapData.order

        // Map to store loaded bitmaps by their original index
        val loadedPieces = mutableMapOf<Int, Bitmap>()

        // Split pieces into chunks based on device tier
        pieces.chunked(parallelLoadCount).forEachIndexed { chunkIndex, chunk ->
            Log.d(TAG, "Loading chunk ${chunkIndex + 1}/${(pieces.size + parallelLoadCount - 1) / parallelLoadCount}")

            // Load pieces in this chunk in parallel
            val chunkResults = chunk.mapIndexed { chunkLocalIndex, piece ->
                val globalIndex = chunkIndex * parallelLoadCount + chunkLocalIndex

                async(Dispatchers.IO) {
                    val fullUrl = "$cdnBase$piece"

                    try {
                        val req = ImageRequest.Builder(context)
                            .data(fullUrl)
                            .allowHardware(false)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .size(
                                width = (estimatedSize.width * 1.2f).toInt(),
                                height = (estimatedSize.height * 1.2f).toInt()
                            )
                            .httpHeaders(NetworkHeaders.Builder().apply {
                                headers.forEach { add(it.key, it.value) }
                            }.build())
                            .build()

                        val bitmap = imageLoader.execute(req).image?.toBitmap()

                        if (bitmap != null) {
                            Log.d(TAG, "✔ Loaded piece[$globalIndex] (${bitmap.width}x${bitmap.height})")
                            globalIndex to bitmap
                        } else {
                            Log.e(TAG, "✘ Failed to load piece[$globalIndex]")
                            null
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading piece[$globalIndex]", e)
                        null
                    }
                }
            }.awaitAll()

            // Store successfully loaded bitmaps
            chunkResults.filterNotNull().forEach { (index, bitmap) ->
                loadedPieces[index] = bitmap
            }

            // Memory management: GC after each chunk on low-end devices
            if (deviceTier == DeviceTier.LOW_END && loadedPieces.size >= memoryCacheSize) {
                System.gc()
            }
        }

        // Reorder according to order array
        return@coroutineScope try {
            order.mapNotNull { orderIndex ->
                loadedPieces[orderIndex] ?: run {
                    Log.e(TAG, "Missing piece at order index $orderIndex")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reordering pieces, using loaded order", e)
            loadedPieces.values.toList()
        }
    }

    private fun drawVertical(
        canvas: Canvas,
        bitmaps: List<Bitmap>,
        targetW: Int,
        targetH: Int
    ) {
        val totalHeight = bitmaps.sumOf { it.height }
        val scale = targetH.toFloat() / totalHeight
        var y = 0f

        bitmaps.forEach { bmp ->
            val scaledW = bmp.width * scale
            val scaledH = bmp.height * scale
            val x = (targetW - scaledW) / 2f

            canvas.drawBitmap(
                bmp,
                null,
                Rect(
                    x.toInt(),
                    y.toInt(),
                    (x + scaledW).toInt(),
                    (y + scaledH).toInt()
                ),
                paint
            )
            y += scaledH
        }
    }

    private fun drawHorizontal(
        canvas: Canvas,
        bitmaps: List<Bitmap>,
        targetW: Int,
        targetH: Int
    ) {
        val totalWidth = bitmaps.sumOf { it.width }
        val scale = targetW.toFloat() / totalWidth
        var x = 0f

        bitmaps.forEach { bmp ->
            val scaledW = bmp.width * scale
            val scaledH = bmp.height * scale
            val y = (targetH - scaledH) / 2f

            canvas.drawBitmap(
                bmp,
                null,
                Rect(
                    x.toInt(),
                    y.toInt(),
                    (x + scaledW).toInt(),
                    (y + scaledH).toInt()
                ),
                paint
            )
            x += scaledW
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        bitmaps: List<Bitmap>,
        targetW: Int,
        targetH: Int,
        cols: Int,
        rows: Int
    ) {
        val cellW = targetW / cols.toFloat()
        val cellH = targetH / rows.toFloat()

        bitmaps.forEachIndexed { i, bmp ->
            val row = i / cols
            val col = i % cols

            val scale = min(cellW / bmp.width, cellH / bmp.height)
            val scaledW = bmp.width * scale
            val scaledH = bmp.height * scale

            val x = col * cellW + (cellW - scaledW) / 2f
            val y = row * cellH + (cellH - scaledH) / 2f

            canvas.drawBitmap(
                bmp,
                null,
                Rect(
                    x.toInt(),
                    y.toInt(),
                    (x + scaledW).toInt(),
                    (y + scaledH).toInt()
                ),
                paint
            )
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap, index: Int): String {
        val name = "combined_${cdnPath}_${System.currentTimeMillis()}_$index.jpg"
        val file = File(context.cacheDir, name)

        // Adjust JPEG quality based on device tier
        val quality = when (deviceTier) {
            DeviceTier.LOW_END -> 85
            DeviceTier.MID_RANGE -> 90
            DeviceTier.HIGH_END -> 95
        }

        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
        }

        return file.absolutePath
    }

    /**
     * Runs cache cleanup in background using IO dispatcher
     * This runs asynchronously and doesn't block the main thread
     */
    private suspend fun cleanOldCacheFilesInBackground() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting background cache cleanup...")

            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            val maxAge = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000L

            // Get all combined image files
            val files = cacheDir.listFiles { file ->
                file.name.startsWith("combined_") && file.name.endsWith(".jpg")
            } ?: return@withContext

            if (files.isEmpty()) {
                Log.d(TAG, "No cache files to clean")
                return@withContext
            }

            // Sort by last modified (oldest first)
            val sortedFiles = files.sortedBy { it.lastModified() }

            var totalSize = sortedFiles.sumOf { it.length() }
            val maxCacheBytes = MAX_CACHE_SIZE_MB * 1024 * 1024
            var deletedCount = 0

            Log.d(TAG, "Found ${files.size} cached files (${totalSize / 1024 / 1024}MB)")

            // Delete old files or files exceeding size limit
            for (file in sortedFiles) {
                val shouldDelete =
                    (now - file.lastModified() > maxAge) || // Too old
                            (totalSize > maxCacheBytes) // Cache too large

                if (shouldDelete) {
                    val fileSize = file.length()
                    if (file.delete()) {
                        totalSize -= fileSize
                        deletedCount++
                        Log.d(TAG, "Deleted: ${file.name} (${fileSize / 1024}KB)")
                    }
                }
            }

            Log.d(TAG, "Background cleanup complete. Deleted: $deletedCount files, Remaining: ${totalSize / 1024 / 1024}MB")
        } catch (e: Exception) {
            Log.e(TAG, "Error in background cache cleanup", e)
        }
    }

    /**
     * Manual cleanup - can be called from UI
     * Runs in background and doesn't block caller
     */
    fun cleanCacheManually() {
        applicationScope.launch {
            cleanOldCacheFilesInBackground()
        }
    }
}