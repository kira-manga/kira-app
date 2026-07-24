package me.manga.kira.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import me.manga.kira.core.dispatchers.platformIoDispatcher

// Foreign-key enforcement parity (source-of-truth: native Android Room).
//
// Native Room on Android opens every connection with PRAGMA foreign_keys = ON by default, so the
// onDelete = CASCADE declared on SavedChapterEntity (saved_chapters -> saved_manga) is actually
// enforced: deleting a saved_manga row cascade-deletes its saved_chapters. KMP routes all targets
// through BundledSQLiteDriver, whose SQLite default is foreign_keys = OFF, so without an explicit
// PRAGMA the declared cascade silently never fires (orphaning saved_chapters on any direct manga
// delete). This callback re-enables FK enforcement on every connection open to match native.
// It is a per-connection runtime PRAGMA only - no schema, column, or DB-version change.
private val ForeignKeysOnCallback = object : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
    }
}

// Room KMP only routes the FIRST pooled connection through the user RoomDatabase.Callback.onOpen;
// every subsequent pooled connection (BundledSQLiteDriver builds a 1-writer/N-reader pool on all
// targets) skips it, so the callback above lands on whichever physical connection opens first. A
// read-first startup would leave the writer with SQLite's default foreign_keys = OFF and the
// declared CASCADEs would never fire. Wrap the driver so the pragma runs on EVERY physical
// connection open, making FK enforcement deterministic across the whole pool.
private class ForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection =
        delegate.open(fileName).also { it.execSQL("PRAGMA foreign_keys = ON") }
}

// Common factory that takes the platform-built builder and finishes wiring it: migration list,
// foreign-key callback, SQLite driver, and the query coroutine context. Returns a ready-to-use
// MangaDatabase singleton.
//
// `setQueryCoroutineContext(platformIoDispatcher)` is the Room KMP-recommended pairing with
// `BundledSQLiteDriver`: it pins suspend `@Query` execution to an IO-offloaded dispatcher across
// all targets (JVM, Android, iOS). An earlier migration note here had dropped the upstream
// `.setQueryCoroutineContext(Dispatchers.IO)` on the assumption that `Dispatchers.IO` was JVM-only;
// that has not been true since kotlinx-coroutines 1.7, so we route through :core's multiplatform
// `platformIoDispatcher` (expect/actual) instead — semantically identical to the legacy :shared
// `IODispatcher` (Dispatchers.IO on JVM/Android, Dispatchers.Default on Native/iOS).
//
// Note: this setting alone does NOT make every Room KMP Flow re-emit on Desktop. The
// `LEFT JOIN ... GROUP BY` aggregate Flow used by the Library (Bug 3) still missed
// invalidation ticks on `BundledSQLiteDriver` after `saved_manga` writes, even though
// Home's simple `SELECT` Flow on the same table did re-emit. The Library workaround now lives
// in `:data` `LibraryRepositoryImpl.observeLibrary()` (which combines two simple per-table Flows
// via the `LibraryMappers.toLibraryManga` shape) — see the `MangaChapterMetrics` header. The
// legacy `LibraryRepository.getDisplayItemsFlow` that pioneered this workaround was retired in
// Phase 9.x.mangadisplayitem.retire.
fun buildMangaDatabase(): MangaDatabase =
    mangaDatabaseBuilder()
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            Migration_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )
        .addCallback(ForeignKeysOnCallback)
        .setDriver(ForeignKeysDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(platformIoDispatcher)
        .build()

/**
 * **Audit-trail postscript** (Phase 9.x.cluster185.staleKdocSweep.cascade,
 * Task #677, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-ninth sibling of the cluster57-184
 * sweep continuum — CLOSING LEAF 5/5 of the wave-55 commonMain :data/local
 * closing-tier 5-leaf batch; MangaDatabaseFactory 5/5).
 *
 *  (a) Inline factory-note prose "Common factory that takes the platform
 *  -built builder and finishes wiring it: migration list, SQLite driver,
 *  and the query coroutine context. Returns a ready-to-use MangaDatabase
 *  singleton" — LIVE-NOT-STALE for the `buildMangaDatabase()` shape
 *  AND FULFILLED-PORT for the Phase 6 Room KMP factory port. Verified the
 *  LIVE chain: `mangaDatabaseBuilder()` (expect call from sibling
 *  `DatabaseBuilder.kt`) → `.addMigrations(...)` (7-migration vararg from
 *  sibling `Migrations.kt` — all 7 names match including the mixed-case
 *  `Migration_4_5`) → `.setDriver(BundledSQLiteDriver())` (Room KMP
 *  recommended driver) → `.setQueryCoroutineContext(IODispatcher)` (per
 *  the prose rationale below) → `.build()`. Three migrations cite the
 *  same sources (MIGRATION_1_2/MIGRATION_5_6/MIGRATION_7_8 confirmed
 *  by cross-reference against `Migrations.kt` cluster185 leaf 4/5).
 *
 *  (b) Inline rationale "`setQueryCoroutineContext(IODispatcher)` is the
 *  Room KMP-recommended pairing with `BundledSQLiteDriver` + earlier
 *  migration note had dropped the upstream `.setQueryCoroutineContext
 *  (Dispatchers.IO)` on the assumption that `Dispatchers.IO` was JVM-only
 *  + that has not been true since kotlinx-coroutines 1.7, so we route
 *  through the project's multiplatform `IODispatcher` (expect/actual)
 *  instead" — LIVE-NOT-STALE for the dispatcher routing rationale AND
 *  FULFILLED-PORT for the Phase 6 cross-target dispatcher routing port:
 *  verified `me.manga.kira.core.concurrency.IODispatcher` import
 *  (line 4); verified IODispatcher is an `expect val IODispatcher:
 *  CoroutineContext` declaration in `:core/concurrency/`, with per-target
 *  actuals (Android = Dispatchers.IO, JVM = Dispatchers.IO, Native =
 *  Dispatchers.Default-based) — confirmed against cluster174 commonMain
 *  core/storage sweep (Task #629). The kotlinx-coroutines 1.7
 *  cross-target `Dispatchers.IO` lift is the upstream fact that obviates
 *  the dropped `.setQueryCoroutineContext` — but the multiplatform
 *  `IODispatcher` expect/actual is preferred for clean layer-separation
 *  per the broader `:core` design.
 *
 *  (c) Inline workaround-note prose "Note: this setting alone does NOT
 *  make every Room KMP Flow re-emit on Desktop + LEFT JOIN ... GROUP BY
 *  aggregate Flow used by the Library (Bug 3) still missed invalidation
 *  ticks on `BundledSQLiteDriver` after `saved_manga` writes + Home's
 *  simple `SELECT` Flow on the same table did re-emit + Library workaround
 *  now lives in `:data` `LibraryRepositoryImpl.observeLibrary()` + combines
 *  two simple per-table Flows via the `LibraryMappers.toLibraryManga`
 *  shape + see the `MangaChapterMetrics` header + legacy
 *  `LibraryRepository.getDisplayItemsFlow` that pioneered this workaround
 *  was retired in Phase 9.x.mangadisplayitem.retire" — LIVE-NOT-STALE
 *  for the Bug-3 workaround documentation AND FULFILLED-RETIRE for the
 *  `LibraryRepository.getDisplayItemsFlow` retire (Task #380 — Phase
 *  9.x.mangadisplayitem.retire). Verified: `getDisplayItemsFlow` is
 *  absent from the source tree (3-pass anchored grep `getDisplayItemsFlow`
 *  / `LibraryRepository.getDisplayItemsFlow` / `displayItemsFlow` — zero
 *  reachers); the `LibraryRepositoryImpl.observeLibrary()` LIVE-replacement
 *  is verified by cross-reference against cluster154 `:data/repository`
 *  sweep (which postscripted LibraryRepositoryImpl with the LIVE
 *  observeLibrary aggregate-Flow shape). The `MangaChapterMetrics` header
 *  reference is LIVE per cluster152 `:data/repository reader-state` sweep
 *  (where MangaChapterMetrics is documented as the LIVE cell-of-truth
 *  for chapter-aggregate metrics on the rework path).
 *
 *  (d) `buildMangaDatabase()` top-level fun — LIVE-NOT-STALE; reached by
 *  Koin binding in `:composeApp/di/SharedModule.kt` (sweep-confirmed
 *  cluster172 §253) as `single<MangaDatabase> { buildMangaDatabase() }`.
 *  The 8-DAO fanout (`mangaDatabase.X()`) reaches the 8 abstract funs
 *  on the sibling `MangaDatabase` class (cluster185 leaf 3/5).
 *
 * --- CLOSING-LEAF SUMMARY (cluster185 commonMain :data/local closing tier) ---
 *
 * The cluster185 wave-55 5-leaf batch sweeps the commonMain :data/local
 * closing tier: the 2 prose-bearing DAO closers (SourcesDao Task #673 +
 * StatisticsDeo Task #674) + the 3 :data/local/ root files (MangaDatabase
 * Task #675 + Migrations Task #676 + MangaDatabaseFactory Task #677).
 * Combined with the cluster183 4-leaf :data/local/converter sweep (Task
 * #638 — Converters + DownloadingStateConverter + LocalDateConverter +
 * LocalDateTimeConverter + StringListConverter; the 5-converter @TypeConverters
 * block of MangaDatabase) and the cluster184 5-leaf :data/local/dao sweep
 * (Task #639 — MangaDao + ChapterDao + HistoryDao + ChapterDownloadDao +
 * NotificationDao), the commonMain :data/local tier is FULLY SWEPT
 * modulo two non-postscripted files: (i) LibraryDeo.kt (bare prose-less,
 * carries only functional step-comments inside @Transaction bodies —
 * zero-classification per the cluster175 precedent), and (ii)
 * DatabaseBuilder.kt (a single-line `expect fun mangaDatabaseBuilder()`
 * declaration with one Phase-6 prose block — deferred to cluster186
 * as a 1-leaf closer).
 *
 * Cumulative Phase-9 retire/prune chain documented across the :data/local
 * tier sweep:
 *   - cluster183 (4 converter leaves): zero retire/prune (all 4 converters
 *     LIVE-NOT-STALE + FULFILLED-PORT for Phase-6 Room KMP TypeConverter port).
 *   - cluster184 (5 DAO leaves): 8 compound retire/prune classifications
 *     across the 5 DAOs (Tasks #386 + #388 + #392 + #394 + #396 + #398 +
 *     #401 + #404 + #441).
 *   - cluster185 leaves 1-2 (2 DAO closers): 4 compound retire/prune
 *     classifications (Tasks #388 + #389 + #390 on SourcesDao + Task #393
 *     on StatisticsDeo).
 *   - cluster185 leaves 3-5 (3 :data/local root files): zero retire/prune
 *     (all 3 LIVE-NOT-STALE + FULFILLED-PORT for the Phase-6 Room KMP
 *     port) modulo 1 FULFILLED-RETIRE cross-reference for
 *     `LibraryRepository.getDisplayItemsFlow` (Task #380) inside
 *     MangaDatabaseFactory's Bug-3 workaround note.
 *
 * Sibling: Migrations.kt (cluster185 prior sibling). CLOSING LEAF 5/5
 * of the cluster185 commonMain :data/local closing-tier 5-leaf batch
 * + CLOSING LEAF of the commonMain :data/local prose-bearing FULLY SWEPT
 * (modulo LibraryDeo bare-prose-less + DatabaseBuilder deferred to
 * cluster186). Compound classification: LIVE-NOT-STALE + FULFILLED-PORT
 * for the Phase 6 Room KMP factory port + FULFILLED-RETIRE for the Task
 * #380 `getDisplayItemsFlow` retire cross-reference. Original Phase-6
 * factory-note + Bug-3 workaround prose preserved verbatim per the
 * audit-trail-preservation convention.
 *
 * CORRECTION (2026-06): section (a) describes a "7-migration vararg ... all 7
 * names match". The `.addMigrations(...)` call (lines 45-55) now registers 9
 * migrations — MIGRATION_1_2 … MIGRATION_9_10 (incl. MIGRATION_8_9 and
 * MIGRATION_9_10), matching MangaDatabase v10. Same staleness the B14 sweep
 * corrected on MangaDatabase.kt.
 */
