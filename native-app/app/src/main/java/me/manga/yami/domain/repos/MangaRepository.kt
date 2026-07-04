package me.manga.yamiapk.domain.repos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.LibraryDeo
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.service.FileService
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaRepository @Inject constructor(
    private val libraryDao: LibraryDeo,
    private val fileService: FileService,
    private val chapterDao: ChapterDao,


    ) {

    val savedMangaTitles: Flow<Set<ApiTitle>> =
        libraryDao
            .getSavedMangaApiTitleFlow()
            .map { it.toSet() }


    suspend fun markChapterAsRead(chapterId: Long) =
        chapterDao.markChapterAsRead(chapterId)

    fun isChapterDownloaded(url: String): Flow<Boolean> =
        chapterDao.isChapterDownloadedFlow(url)

    suspend fun insert(manga: SavedMangaEntity):Long {
        return libraryDao.insertManga(manga)
    }
    suspend fun save(manga: SavedMangaEntity, chapters: List<SavedChapterEntity>) {
        libraryDao.saveMangaWithChapters(manga, chapters)
    }


    suspend fun removeManga(title: String){
        val mangaId = libraryDao.getMangaIdByTitle(title) ?: return
        libraryDao.removeMangaWithChapters(mangaId)
        fileService.deleteMangaFiles(mangaId)
    }

    suspend fun removeMangaById(mangaId: Long){
        fileService.deleteMangaFiles(mangaId)
        libraryDao.removeMangaWithChapters(mangaId)

    }




}