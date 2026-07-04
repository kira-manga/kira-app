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
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.manga.yamiapk.sources_repositry.ar.promanga.models.ImageCombinerState
import java.io.File
import kotlin.math.min

private const val TAG = "ProMangaImageCombiner"
private const val MAX_CANVAS_DIMENSION = 4096 // Prevent OOM
private const val MEMORY_CACHE_SIZE = 3 // Keep only 3 bitmaps in memory at once

@Serializable
data class ImageMapMetadata(
    val dim: List<Int>,
    val mode: String,
    val pieces: List<String>,
    val order: List<Int>
)

@Serializable
data class ProMangaChapterResponse(
    val id: Int,
    val content_id: Int,
    val chapter_number: String,
    val title: String? = null,
    val language: String? = null,
    val translator: String? = null,
    val uploader_id: Int,
    val status: String,
    val cdn_path: String,
    val metadata: ChapterMetadata
)

@Serializable
data class ChapterData(
    val id: Int,
    val cdn_path: String? = "",
    val metadata: ChapterMetadata
)

@Serializable
data class ChapterMetadata(
    val images: List<String>? = listOf(),
    val maps: List<ImageMapMetadata>? = listOf()
)