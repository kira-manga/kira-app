package me.manga.yamiapk.presentation.features.reader.data

import androidx.compose.ui.graphics.painter.BitmapPainter
import coil3.request.ImageRequest
import me.manga.yamiapk.domain.model.ReaderChapters

sealed class ReaderItem {
    data class ImagePage(
        val request: ImageRequest,
        val chapterIndex: Int,
        val isCompressed: Boolean = false,
        val compressedPainter: BitmapPainter? = null
    ) : ReaderItem()

    data class NextChapterOverlay(
        val currentChapter: ReaderChapters,
        val nextChapter: ReaderChapters
    ) : ReaderItem()

    data class ErrorOverlay(
        val currentChapter: ReaderChapters,
        val errorCode:  Int?,


        val errorMassage: String
    ) : ReaderItem()
}