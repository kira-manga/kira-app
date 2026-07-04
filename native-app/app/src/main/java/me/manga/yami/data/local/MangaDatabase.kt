package me.manga.yamiapk.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import me.manga.yamiapk.data.local.converter.Converters
import me.manga.yamiapk.data.local.converter.DownloadingStateConverter
import me.manga.yamiapk.data.local.converter.LocalDateConverter
import me.manga.yamiapk.data.local.converter.LocalDateTimeConverter
import me.manga.yamiapk.data.local.converter.StringListConverter
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.dao.LibraryDeo
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.local.dao.StatisticsDeo
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.data.local.entity.SourcesEntity

@Database(
    entities = [
        SavedMangaEntity::class,
        SavedChapterEntity::class,
        HistoryItemD::class,
        ChapterNotification::class,
        ChapterDownloadEntity::class,
        SourcesEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(
    DownloadingStateConverter::class,
    StringListConverter::class,
    Converters::class,             // your existing converters
    LocalDateConverter::class,     // for LocalDate
    LocalDateTimeConverter::class  // <-- new
)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun libraryDeo(): LibraryDeo
    abstract fun notificationDao(): NotificationDao
    abstract fun statisticsDeo(): StatisticsDeo
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterDownloadingDao(): ChapterDownloadDao
    abstract fun sourcesDao(): SourcesDao

    //
    companion object {
        const val DATABASE_NAME = "manga_database"
    }

}


