package me.manga.kira.data.local.di

import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.buildMangaDatabase
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.dao.SourceConfigCacheDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.dao.StatisticsDeo
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Room database + DAO Koin bindings — relocated here (strangler-fig Phase 1) from the three legacy
 * `PlatformModule.{android,ios,desktop}` actuals, which each declared this identical block.
 *
 * `buildMangaDatabase()` is commonMain (it calls the per-target `expect fun mangaDatabaseBuilder()`
 * actual), so a single common module serves all platforms — no per-target Koin module needed.
 *
 * Wired into the graph via `allSharedModules()` in :shared, which is the one list already threaded
 * through `initKoin` (Android/Desktop), the iOS `doInitKoin`, AND the `KoinGraphResolutionTest` union —
 * so this single registration reaches every host and the deep DI gate.
 *
 * Android ordering: `setAndroidAppContext(applicationContext)` MUST run in `MyApp.onCreate()` before
 * `MangaDatabase` is first resolved, or `mangaDatabaseBuilder()` throws a clear IllegalStateException.
 */
fun databaseModule(): Module = module {
    single<MangaDatabase> { buildMangaDatabase() }
    single<HistoryDao> { get<MangaDatabase>().historyDao() }
    single<LibraryDeo> { get<MangaDatabase>().libraryDeo() }
    single<NotificationDao> { get<MangaDatabase>().notificationDao() }
    single<StatisticsDeo> { get<MangaDatabase>().statisticsDeo() }
    single<MangaDao> { get<MangaDatabase>().mangaDao() }
    single<ChapterDao> { get<MangaDatabase>().chapterDao() }
    single<ChapterDownloadDao> { get<MangaDatabase>().chapterDownloadingDao() }
    single<SourcesDao> { get<MangaDatabase>().sourcesDao() }
    single<SourceConfigCacheDao> { get<MangaDatabase>().sourceConfigCacheDao() }
}
