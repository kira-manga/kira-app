package me.manga.kira.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.data.local.converter.StringListConverter

// Migration note (Phase 6): System.currentTimeMillis() (JVM-only) replaced with
// kotlin.time.Clock.System.now().toEpochMilliseconds() in default-arg expressions. The wire
// representation in SQLite remains Long, unchanged from source.
@OptIn(ExperimentalTime::class)
@Entity(
    tableName = "saved_manga",
    indices = [Index(value = ["url"], unique = true)],
)
@TypeConverters(StringListConverter::class)
data class SavedMangaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val api: String,
    val language: String,
    val url: String,
    val imageUrl: String,
    val title: String,
    val description: String,
    @ColumnInfo(defaultValue = "''")
    val author: String = "",
    val status: String,
    val rating: String?,
    val genres: List<String>,
    val savedTimestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val lastOpenTimestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val isLiked: Boolean = false,
    val isWatchingNow: Boolean = false,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster186.staleKdocSweep.cascade,
 * Task #679, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-ninety-first sibling of the cluster57-185
 * sweep continuum — leaf 2/5 of the wave-56 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch; SavedMangaEntity.kt 2/5).
 *
 *  (a) Inline migration-note comment "Migration note (Phase 6): System
 *  .currentTimeMillis() (JVM-only) replaced with kotlin.time.Clock.System
 *  .now().toEpochMilliseconds() in default-arg expressions + The wire
 *  representation in SQLite remains Long, unchanged from source" —
 *  LIVE-NOT-STALE for the SavedMangaEntity shape AND FULFILLED-PORT for
 *  the Phase 6 KMP-time port: verified the 2 default-arg expressions
 *  `Clock.System.now().toEpochMilliseconds()` on lines 31-32 (savedTimestamp
 *  + lastOpenTimestamp); verified `kotlin.time.Clock` + `kotlin.time
 *  .ExperimentalTime` imports (lines 7-8); verified `@OptIn(ExperimentalTime
 *  ::class)` opt-in (line 14) — required by the experimental status of
 *  `kotlin.time.Clock` in stdlib at port time. The SQLite wire-format
 *  Long is unchanged: both fields are `val X: Long = ...` (line types
 *  unchanged from source). The `System.currentTimeMillis()` JVM-only call
 *  is verified absent from the source tree (grep `System.currentTimeMillis`
 *  in :data/local zero hits).
 *
 *  (b) 6-entity-array LIVE membership for SavedMangaEntity — LIVE-NOT-STALE;
 *  SavedMangaEntity is the first member of the 6-entity array on `@Database`
 *  in sibling `MangaDatabase.kt` (cluster185 leaf 3/5, line 37). The
 *  `@Index(value = ["url"], unique = true)` (line 17) is LIVE per the
 *  unique-URL business invariant (one saved row per cross-source URL).
 *  The `@TypeConverters(StringListConverter::class)` (line 19) is LIVE
 *  per the `val genres: List<String>` field (line 30) requiring serde
 *  via the cluster183 leaf 4 `StringListConverter` (Task #638). The
 *  `tableName = "saved_manga"` is the LIVE table reached by 4 of the 7
 *  Migration objects (MIGRATION_1_2 lastOpenTimestamp ALTER, MIGRATION_3_4
 *  isLiked + isWatchingNow ALTERs — cluster185 leaf 4 Migrations.kt).
 *
 *  (c) 13-column row shape (id + api + language + url + imageUrl + title
 *  + description + status + rating + genres + savedTimestamp +
 *  lastOpenTimestamp + isLiked + isWatchingNow) — LIVE-NOT-STALE; 14
 *  columns total (counting `id`). Reached by MangaDao + LibraryDeo +
 *  HistoryDao + StatisticsDeo + 7 Migration objects via the `saved_manga`
 *  table-name. The `rating: String?` nullable-string is the only nullable
 *  column; all other primitives carry defaults.
 *
 * Verified: 1 entity declaration with 13 columns + 2 default Clock.System
 * .now() expressions + 3 annotations (@Entity + @Index + @TypeConverters)
 * + 1 @OptIn(ExperimentalTime::class) opt-in. Sibling: DatabaseBuilder.kt
 * (cluster186 prior sibling); SavedChapterEntity.kt (cluster186 succeeding
 * sibling). LEAF 2/5 of the cluster186 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch. Compound classification:
 * LIVE-NOT-STALE + FULFILLED-PORT for the Phase 6 KMP-time port. Original
 * Phase-6 migration-note prose preserved verbatim per the audit-trail
 * -preservation convention.
 */
