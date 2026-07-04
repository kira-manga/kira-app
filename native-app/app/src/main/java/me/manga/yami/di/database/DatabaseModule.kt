package me.manga.yamiapk.di.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.data.local.*
import me.manga.yamiapk.data.local.MangaDatabase
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.dao.LibraryDeo
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.local.dao.StatisticsDeo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMangaDatabase(
        @ApplicationContext context: Context
    ): MangaDatabase = Room.databaseBuilder(
        context,
        MangaDatabase::class.java,
        MangaDatabase.DATABASE_NAME
    )
//    .fallbackToDestructiveMigration()
        .addMigrations(

            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            Migration_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8  // Add the new migration

        )

        .build()

    @Provides
    @Singleton
    fun provideHistoryDao(database: MangaDatabase): HistoryDao = database.historyDao()
    @Provides
    @Singleton
    fun provideLibraryDeo(database: MangaDatabase): LibraryDeo = database.libraryDeo()

    @Provides
    @Singleton
    fun provideNotificationDao(database: MangaDatabase): NotificationDao = database.notificationDao()

    @Provides
    @Singleton
    fun provideStatisticsDao(database: MangaDatabase): StatisticsDeo = database.statisticsDeo()


    @Provides
    @Singleton
    fun provideMangaDao(database: MangaDatabase): MangaDao = database.mangaDao()

    @Provides
    @Singleton
    fun provideChapterDao(database: MangaDatabase): ChapterDao = database.chapterDao()
    @Provides
    @Singleton
    fun provideChaptersDownloadDao(database: MangaDatabase): ChapterDownloadDao = database.chapterDownloadingDao()

    @Provides
    @Singleton
    fun provideSourcesDao(database: MangaDatabase): SourcesDao = database.sourcesDao()


}