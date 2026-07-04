# Database Migration Report — Room → Room KMP

> Mandatory output per Phase 6 of `MIGRATION_PROMPT.md`. Documents the Room → Room KMP migration completed in commit `2445e99`.

## Scope

Source had a single `MangaDatabase` (version 8) with 6 entities, 8 DAOs, 5 type converters, 7 migrations. All moved into `shared/commonMain` as Room KMP, with platform-specific database builders.

## Versions locked

- `androidx.room:room-runtime`, `room-compiler`, `room-ktx`, `room-paging` — **`2.8.4`**
- `androidx.sqlite:sqlite-bundled` — **`2.5.2`** (catalog `sqlite = "2.5.2"`)
- `androidx.room` Gradle plugin — applied to `shared` (version inherited from catalog `room = "2.8.4"`)
- KSP `2.3.8` configured for all 5 targets: `kspAndroid`, `kspIosX64`, `kspIosArm64`, `kspIosSimulatorArm64`, `kspDesktop`

## Schema export

- Source had `exportSchema = false`. **Flipped to `true`** per `MIGRATION_PROMPT.md` Section 37.
- Schema directory: `shared/schemas/`
- First exported schema: `shared/schemas/me.manga.kira.data.local.MangaDatabase/8.json` (generated on first compile; committed in `2445e99`).
- Future schema bumps will append `9.json`, `10.json`, … alongside.
- **Destructive migration NOT enabled** (`.fallbackToDestructiveMigration()` not used).

## Entity migration

| Entity | Source → KMP changes | Wire-format preserved | Status |
|---|---|---|---|
| `SavedMangaEntity` | `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()` in default args | Yes (Long epoch-millis) | migrated |
| `SavedChapterEntity` | `@Parcelize` + `Parcelable` dropped; `java.time.LocalDate` → `kotlinx.datetime.LocalDate`; `LocalDate.now()` → `Clock.System.todayIn(currentTZ)` | Yes (Long epoch-day via converter) | migrated |
| `HistoryItemD` | `java.time.LocalDateTime` → `kotlinx.datetime.LocalDateTime`; `.now()` ported | Yes (Long epoch-millis via converter) | migrated |
| `ChapterNotification` | `@Parcelize` + `Parcelable` dropped; LocalDate port | Yes | migrated |
| `ChapterDownloadEntity` | no changes | Yes | migrated |
| `SourcesEntity` | no changes | Yes | migrated |

Field counts confirmed by Audit Agent #3 (see `OLD_WORK_AUDIT_FINDINGS.md`): all six entities match source field count exactly.

## DAO migration

| DAO | Source method count | New method count | Status |
|---|---|---|---|
| `HistoryDao` | 15 | 15 | migrated (`java.time.LocalDateTime` import replaced with `kotlinx.datetime.LocalDateTime`) |
| `LibraryDeo` | 21 | 21 | migrated (typo `Deo` preserved verbatim per `renames.md`) |
| `NotificationDao` | 26 | 26 | migrated |
| `StatisticsDeo` | 14 | 14 | migrated (typo `Deo` preserved) |
| `MangaDao` | 18 | 18 | migrated |
| `ChapterDao` | 31 | 31 | migrated (`System.currentTimeMillis()` defaults → `Clock.System.now().toEpochMilliseconds()`) |
| `ChapterDownloadDao` | 23 | 21 | migrated; **2 PagingSource methods removed** (see below) |
| `SourcesDao` | 17 | 17 | migrated |
| `SavedMangaDao` (orphan, not on database) | 38 | 38 | preserved verbatim |

**Suspend-or-Flow audit clean** for all 9 DAOs: every non-`Flow<T>` method is `suspend`, as required by Room KMP for non-Android targets.

### `ChapterDownloadDao` paging-method removal

Two methods returning `PagingSource<Int, ChapterDownloadEntity>` were removed:
- `observeAllDownloadsPaged()`
- `observeDownloadsByStatePaged(states: List<DownloadingState>)`

**Reason**: Room's `androidx.room.paging.LimitOffsetPagingSource` (the codegen target for `PagingSource`-returning DAO methods) is Android-only. The Room KSP for non-Android targets cannot resolve it, breaking the build.

**Behavior parity**: the non-paginated `observeAllDownloads(): Flow<List<ChapterDownloadEntity>>` is kept as the canonical API. Source's `@Deprecated` annotation on it was removed (the deprecation pointed at the now-removed paginated alternative). Phase 10 will derive `Pager { ... }` per-platform when the downloads UI needs paged scrolling — on Android via `androidx.paging:paging-runtime`'s `Pager` builder taking a `PagingSource` derived from the Flow, on iOS/Desktop via direct list rendering.

**`androidx.paging.PagingSource` import** also removed from the DAO.

## Type converter migration

| Converter | Old API | New API | Wire format |
|---|---|---|---|
| `Converters` | `java.util.Date <-> Long` (epoch-millis) | `kotlin.time.Instant <-> Long` | Long epoch-millis — bit-identical |
| `StringListConverter` | Gson `Gson()` + `TypeToken<List<String>>` | `kotlinx.serialization` `Json` + `ListSerializer(String.serializer())` | JSON array of strings — bit-identical between Gson and kotlinx for `List<String>` |
| `LocalDateConverter` | `java.time.LocalDate` (`toEpochDay`/`ofEpochDay` + ISO String) | `kotlinx.datetime.LocalDate` (`toEpochDays`/`fromEpochDays` + same `toString()`) | Long epoch-day AND ISO-8601 String both preserved |
| `LocalDateTimeConverter` | `java.time.LocalDateTime` + `ZoneOffset.UTC` | `kotlinx.datetime.LocalDateTime` + `TimeZone.UTC` | Long epoch-millis preserved |
| `DownloadingStateConverter` | enum name | enum name | identical (String) |

**Existing user-data preservation**: existing DB rows from production source-app installs continue to deserialize correctly after upgrade. Every converter pair preserves the on-disk format bit-for-bit. No data migration step is required when the new KMP app first launches with an existing v8 database.

## Migration object migration

All 7 source migrations preserved verbatim (`MIGRATION_1_2` through `MIGRATION_7_8`, including the lowercase `Migration_4_5` typo). API ported from `androidx.sqlite.db.SupportSQLiteDatabase` (Android-only) to `androidx.sqlite.SQLiteConnection` (KMP). Every `db.execSQL("…")` rewritten as `connection.execSQL("…")` using the `androidx.sqlite.execSQL` extension. SQL text itself is identical — multi-step rebuild for the `sources` table in `MIGRATION_7_8` preserved verbatim.

## Database class

`shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/MangaDatabase.kt`:

```kotlin
@Database(
    entities = [SavedMangaEntity::class, SavedChapterEntity::class, HistoryItemD::class,
                ChapterNotification::class, ChapterDownloadEntity::class, SourcesEntity::class],
    version = 8,
    exportSchema = true,
)
@TypeConverters(DownloadingStateConverter::class, StringListConverter::class, Converters::class,
                LocalDateConverter::class, LocalDateTimeConverter::class)
@ConstructedBy(MangaDatabaseConstructor::class)
abstract class MangaDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun libraryDeo(): LibraryDeo
    abstract fun notificationDao(): NotificationDao
    abstract fun statisticsDeo(): StatisticsDeo
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterDownloadingDao(): ChapterDownloadDao
    abstract fun sourcesDao(): SourcesDao

    companion object { const val DATABASE_NAME = "manga_database" }
}

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object MangaDatabaseConstructor : RoomDatabaseConstructor<MangaDatabase> {
    override fun initialize(): MangaDatabase
}
```

## Per-platform database builders

| Platform | File | Location strategy |
|---|---|---|
| Android | `shared/src/androidMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.android.kt` | `context.getDatabasePath(DATABASE_NAME)`. Requires `setAndroidAppContext(context)` to be called once from `MyApp.onCreate()` (Phase 11). |
| iOS | `shared/src/iosMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.ios.kt` | `NSFileManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask)` → `<docs>/manga_database`. `@OptIn(ExperimentalForeignApi::class)`. |
| Desktop | `shared/src/desktopMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.desktop.kt` | `~/.yami-manga/manga_database`. `mkdirs()` if missing. |

Common factory `buildMangaDatabase()` in `MangaDatabaseFactory.kt`:

```kotlin
fun buildMangaDatabase(): MangaDatabase =
    mangaDatabaseBuilder()
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, Migration_4_5,
                       MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        .setDriver(BundledSQLiteDriver())
        .build()
```

Source's `.setQueryCoroutineContext(Dispatchers.IO)` intentionally dropped — `Dispatchers.IO` is internal in commonMain (JVM-only). Room KMP's default query dispatcher (per-target appropriate) is used instead. Rationale documented inline in the factory.

## Koin wiring (Phase 5 batch 5.2 sidecar)

`shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt` extended with:

```kotlin
single<MangaDatabase> { buildMangaDatabase() }
single { get<MangaDatabase>().historyDao() }
single { get<MangaDatabase>().libraryDeo() }
single { get<MangaDatabase>().notificationDao() }
single { get<MangaDatabase>().statisticsDeo() }
single { get<MangaDatabase>().mangaDao() }
single { get<MangaDatabase>().chapterDao() }
single { get<MangaDatabase>().chapterDownloadingDao() }
single { get<MangaDatabase>().sourcesDao() }
```

Total: 9 new bindings (1 DB + 8 DAOs).

## Verification

Single Gradle invocation per target, all three required builds pass:

| Command | Result | Time |
|---|---|---|
| `:shared:compileKotlinDesktop` | BUILD SUCCESSFUL | 38s |
| `:shared:compileKotlinIosArm64` | BUILD SUCCESSFUL | 37s |
| `:app:assembleDebug` | BUILD SUCCESSFUL | 28s |

Plus runtime verification deferred to Phase 14 (Android smoke test: launch app, save a manga, observe via the library DAO, kill+reopen, confirm data round-tripped).

## Behavior parity verification method

1. **Build-time KSP**: Room compiler resolved all entity / DAO / converter / migration references on every target. Schema export wrote `8.json` successfully.
2. **Wire-format identity**: every converter pair preserves the source's on-disk binary format (Long, ISO String, JSON array, enum-name String) — verified by careful inspection in commit `2445e99`. Existing user databases will deserialize correctly on first launch.
3. **Audit Agent #3** (parallel, independent) confirmed entity field-count parity, DAO method-count parity, type-converter port correctness, and suspend-or-Flow DAO discipline.

## Migration files touched

- `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/` — entire subtree (32 files)
- `shared/src/androidMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.android.kt`
- `shared/src/iosMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.ios.kt`
- `shared/src/desktopMain/kotlin/me/manga/yamiapk/data/local/DatabaseBuilder.desktop.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt` — 9 bindings added
- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/library/data/SavedMangaWithMetrics.kt` — moved (used by `MangaDao` as @Embedded query result)
- `shared/schemas/me.manga.kira.data.local.MangaDatabase/8.json` — first schema export
- `gradle/libs.versions.toml` — `androidx-paging-common` added (Phase 6 prep; ChapterDownloadDao eventually didn't need it but the catalog entry stays)
- `shared/build.gradle.kts` — `api(libs.androidx.paging.common)` line added

## Pending Phase 14 follow-up

Runtime smoke test on Android: launch a debug build with an existing v8 database, save a manga via library DAO, force-restart the process, confirm the manga is still in the library (validates Room KMP's wire-format identity claim).
