package me.manga.yamiapk.core.file

import android.content.Context
import me.manga.yamiapk.R
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

object FileSizeUtils {
    /**
     * Calculate the total size of all files in a chapter directory
     * @param context Application context
     * @param mangaId Manga ID
     * @param chapterId Chapter ID
     * @return Size in bytes, or 0 if directory doesn't exist
     */
    fun getChapterSize(context: Context, mangaId: Long, chapterId: Long): Long {
        val chapterDir = File(context.filesDir, "manga/$mangaId/chapter_$chapterId")

        return safeWalkFiles(chapterDir)
            .sumOf { it.length() }
    }

    /**
     * Calculate the total size of all files for a manga (all chapters)
     * @param context Application context
     * @param mangaId Manga ID
     * @return Total size in bytes
     */
    fun getTotalMangaSize(context: Context, mangaId: Long): Long {
        val mangaDir = File(context.filesDir, "manga/$mangaId")

        return safeWalkFiles(mangaDir)
            .sumOf { it.length() }
    }

    /**
     * Format bytes to human-readable string (KB, MB, GB, etc.)
     * @param bytes Size in bytes
     * @return Formatted string like "15.2 MB" or "512 KB"
     */

    fun Context.formatBytes(bytes: Long): String {
        if (bytes <= 0) return getString(R.string.bytes_zero)

        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        val value = bytes / 1024.0.pow(digitGroups.toDouble())

        return when (digitGroups) {
            0 -> getString(R.string.bytes_format_b, value)
            1 -> getString(R.string.bytes_format_kb, value)
            2 -> getString(R.string.bytes_format_mb, value)
            3 -> getString(R.string.bytes_format_gb, value)
            4 -> getString(R.string.bytes_format_tb, value)
            else -> getString(R.string.bytes_format_tb, value)
        }
    }
    /**
     * Get formatted file size for a chapter
     * @param context Application context
     * @param mangaId Manga ID
     * @param chapterId Chapter ID
     * @return Formatted string like "15.2 MB" or empty string if not downloaded
     */
    fun getFormattedChapterSize(context: Context, mangaId: Long, chapterId: Long): String {
        val size = getChapterSize(context, mangaId, chapterId)
        return if (size > 0) context.formatBytes(size) else ""
    }

    /**
     * Get formatted total manga size
     * @param context Application context
     * @param mangaId Manga ID
     * @param chapters List of chapters
     * @return Formatted string like "150.5 MB" or empty string if nothing downloaded
     */
    fun getFormattedMangaSize(context: Context, mangaId: Long): String {
        val size = getTotalMangaSize(context, mangaId)
        return if (size > 0) context.formatBytes(size) else ""
    }

    private fun safeWalkFiles(root: File): Sequence<File> = sequence {
        if (!root.exists() || !root.isDirectory) return@sequence

        val stack = ArrayDeque<File>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()

            val list = dir.listFiles() ?: continue  // Prevent crash

            for (file in list) {
                if (file.isDirectory) {
                    stack.add(file)
                } else {
                    yield(file)
                }
            }
        }
    }
}