package me.manga.yamiapk.domain.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileService @Inject constructor(
    @ApplicationContext private val context: Context
) {
     fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
       try {
           val mangaDir = File(context.filesDir, "manga/$mangaId")
           val chapterDir = File(mangaDir, "chapter_$chapterId")

           if (chapterDir.exists()) {
               chapterDir.deleteRecursively()
           }
       }catch (e : Exception){


       }

    }

    suspend fun deleteMangaFiles(mangaId: Long) = withContext(Dispatchers.IO) {
        val mangaDir = File(context.filesDir, "manga/$mangaId")
        if (mangaDir.exists()) {
            mangaDir.deleteRecursively()
        }
    }
} 