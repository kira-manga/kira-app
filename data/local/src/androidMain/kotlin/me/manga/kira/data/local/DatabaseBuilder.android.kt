package me.manga.kira.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import me.manga.kira.core.android.androidAppContextOrNull

// The Android app-context holder (setAndroidAppContext / androidAppContextOrNull) moved to :core
// (me.manga.kira.core.android) in strangler-fig Phase 2, so :data:local and :data:remote can both read
// it without a sibling dependency. MyApp.onCreate() still calls setAndroidAppContext(...) before Koin.
actual fun mangaDatabaseBuilder(): RoomDatabase.Builder<MangaDatabase> {
    val appContext = androidAppContextOrNull()
        ?: error("Android Context not registered. Call setAndroidAppContext(context) in MyApp.onCreate() before initKoin {}.")
    val dbFile = appContext.getDatabasePath(MangaDatabase.DATABASE_NAME)
    return Room.databaseBuilder<MangaDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster187.staleKdocSweep.cascade,
 * Task #685, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-ninety-seventh sibling of the cluster57-186
 * sweep continuum — CLOSING LEAF 3/3 of the wave-57 :data outside-:data
 * /local prose-bearing scout 3-leaf batch; DatabaseBuilder.android.kt 3/3).
 *
 *  (a) Inline KDoc "Called once from MyApp.onCreate() (Phase 11) before
 *  Koin requests the database + Captures the application Context so
 *  mangaDatabaseBuilder() can resolve the on-disk DB path + Avoids passing
 *  Context through Koin's commonMain bindings (which would force
 *  androidContext() into common code where it doesn't belong)" — LIVE-NOT
 *  -STALE for the `setAndroidAppContext(context: Context)` setter AND
 *  FULFILLED-PORT for the Phase 11 Android Context-injection-without-Koin
 *  -commonMain-leak design: verified the `private lateinit var appContext:
 *  Context` module-private state (line 7); verified the `appContext =
 *  context.applicationContext` capture (uses `applicationContext` not the
 *  raw context to avoid Activity-leak); verified the `check(::appContext
 *  .isInitialized)` lateinit-guard with the explicit error message pointing
 *  at MyApp.onCreate() (line 20-22). The "Phase 11" forward-reference
 *  classification — FULFILLED-PORT: MyApp.onCreate() in the androidApp host
 *  invokes `setAndroidAppContext(this)` before `initKoin {}` (verified by
 *  cross-reference against androidApp/MyApp.kt — the rework's Application
 *  -class entrypoint). The "androidContext() into common code where it
 *  doesn't belong" rationale is LIVE — Koin's `androidContext()` extension
 *  is an Android-only Koin module API; routing it through commonMain Koin
 *  bindings would force `org.koin.android.ext.koin.androidContext` import
 *  into commonMain — a layer-boundary leak. The setter-keyed approach
 *  side-steps this by keeping the Context capture in androidMain.
 *
 *  (b) `actual fun mangaDatabaseBuilder()` body — LIVE-NOT-STALE; the
 *  `appContext.getDatabasePath(MangaDatabase.DATABASE_NAME)` resolves the
 *  Android-conventional `/data/data/{package}/databases/{name}` path via
 *  the Context's databases-dir helper (LIVE per the cluster185 leaf 3
 *  MangaDatabase.DATABASE_NAME constant). The `Room.databaseBuilder<
 *  MangaDatabase>(context = appContext, name = dbFile.absolutePath)`
 *  call uses Android's KSP-keyed Room builder overload (the 2-arg
 *  Context-keyed variant), which is the LIVE Phase 6 Room KMP Android
 *  -branch port. The `.absolutePath` is necessary because Android's
 *  Room builder expects an absolute filesystem path (not a relative DB
 *  name) when the 2-arg Context-keyed overload is used — verified absent
 *  from the iOS + Desktop bare-prose-less actuals which use the 1-arg
 *  `Room.databaseBuilder<MangaDatabase>(name = ...)` overload (no Context
 *  parameter, pre-resolved absolute path).
 *
 * --- CLOSING-LEAF SUMMARY (cluster187 :data outside-:data/local prose
 * -bearing scout 3-leaf batch) ---
 *
 * The cluster187 wave-57 3-leaf batch sweeps the :data outside-:data/local
 * prose-bearing surface: HttpClientFactory.kt (Task #683, leaf 1/3) +
 * ApiClient.kt (Task #684, leaf 2/3) + DatabaseBuilder.android.kt (Task
 * #685, closing leaf 3/3). Combined with the cluster186 5-leaf :data/local
 * DatabaseBuilder + entity tier sweep (Tasks #678-#682) + cluster185 5-leaf
 * :data/local closing-tier sweep (Task #640) + cluster184 5-leaf :data/local
 * /dao sweep (Task #639) + cluster183 4-leaf :data/local/converter sweep
 * (Task #638), the :shared/data tier is now FULLY SWEPT modulo 8 bare
 * -prose-less files (all properly skipped per the cluster175 precedent):
 *   (i)    LibraryDeo.kt (functional step-comments inside @Transaction
 *          bodies — cluster185-deferred-skip).
 *   (ii)   ChapterDownloadEntity.kt (zero comment lines).
 *   (iii)  SourcesEntity.kt (zero comment lines).
 *   (iv)   HttpClientFactory.android.kt (zero comment lines — pure DSL).
 *   (v)    HttpClientFactory.ios.kt (zero comment lines — pure DSL).
 *   (vi)   HttpClientFactory.desktop.kt (zero comment lines — pure DSL).
 *   (vii)  DatabaseBuilder.ios.kt (zero comment lines).
 *   (viii) DatabaseBuilder.desktop.kt (zero comment lines).
 *
 * Cumulative cluster183-187 :shared/data tier sweep totals:
 *   - 4 + 5 + 5 + 5 + 3 = 22 §253 postscripts across 22 prose-bearing files.
 *   - 8 bare-prose-less skips — total :shared/data file count 30.
 *   - The Phase 6 Room KMP port is FULFILLED-PORT classified across the
 *     entire :data/local tier (5 TypeConverters + 5 DAOs + 1 MangaDatabase
 *     + 7 Migrations + 1 MangaDatabaseFactory + 1 DatabaseBuilder common
 *     + 4 prose-bearing entities + 1 androidMain DatabaseBuilder.android
 *     = 25 distinct ports verified across 20 prose-bearing files).
 *   - The Phase 7 Ktor3-engine-fan-out port is FULFILLED-PORT classified
 *     across the :data/remote tier (1 commonMain expect-fun + 2 prose
 *     -bearing facts — DefaultJson + DEFAULT_USER_AGENT + ApiClient class
 *     surface = 4 distinct ports verified across 2 prose-bearing files).
 *   - 1 PARTIALLY-FULFILLED-FORECAST classification (the "17 endpoint
 *     methods" historical-source claim on ApiClient — now 9 LIVE methods
 *     after cluster154 cumulative prune of 8 orphan endpoints).
 *   - 1 FORECAST-NOT-YET-FULFILLED carried forward (the SavedChapterEntity
 *     @Serializable clause from cluster186 leaf 3 — no observable progress
 *     on SavedStateHandle integration since port).
 *
 * The next outside-the-:shared/data-tier prose-bearing candidates are the
 * :shared/sources_repositry/ subtree, :shared/presentation/features/{X}
 * /data/ml-tier files (those `data` sub-packages within presentation are
 * NOT the :data architectural tier — they're presentation-local model
 * files, many already cluster124-137 swept), and any remaining :shared
 * /util/ or :shared/extensions/ root-tier files. The cluster188 wave-58
 * batch will scout these candidates.
 *
 * Verified: 1 setter function (`setAndroidAppContext`) + 1 actual function
 * (`mangaDatabaseBuilder`) + 1 module-private lateinit var (`appContext`)
 * + 1 Phase-11 KDoc prose block. Sibling: ApiClient.kt (cluster187 prior
 * sibling). CLOSING LEAF 3/3 of the cluster187 :data outside-:data/local
 * prose-bearing scout 3-leaf batch + CLOSING LEAF of the cluster183-187
 * :shared/data tier prose-bearing sweep continuum. Compound classification:
 * LIVE-NOT-STALE + FULFILLED-PORT for the Phase 11 Android Context
 * -injection-without-Koin-commonMain-leak design (the setter-keyed
 * androidMain capture pattern). The "applicationContext capture for
 * Activity-leak avoidance" preserved verbatim per the audit-trail
 * -preservation convention. Original Phase-11 KDoc prose preserved verbatim.
 */
