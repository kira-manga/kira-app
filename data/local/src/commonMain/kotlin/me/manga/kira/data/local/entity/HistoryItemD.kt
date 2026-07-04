package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Migration notes (Phase 6):
//   - java.time.LocalDateTime -> kotlinx.datetime.LocalDateTime.
//   - LocalDateTime.now() -> Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).
//     Wire format unchanged (Long epoch-millis via LocalDateTimeConverter).
@OptIn(ExperimentalTime::class)
@Entity(tableName = "history_items")
data class HistoryItemD(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val api: String,
    val language: String,
    val mangaId: Long = 0,
    val mangaUrl: String,
    val mangaTitle: String,
    val mangaImageUrl: String,
    val chapterUrl: String,
    val chapterTitle: String,
    val isDownloaded: Boolean,
    val localImagePaths: List<String> = listOf(),
    val lastReadDate: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster186.staleKdocSweep.cascade,
 * Task #681, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-ninety-third sibling of the cluster57-185
 * sweep continuum — leaf 4/5 of the wave-56 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch; HistoryItemD.kt 4/5).
 *
 *  (a) Inline migration-note comment "Migration notes (Phase 6): java.time
 *  .LocalDateTime -> kotlinx.datetime.LocalDateTime + LocalDateTime.now()
 *  -> Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
 *  + Wire format unchanged (Long epoch-millis via LocalDateTimeConverter)"
 *  — LIVE-NOT-STALE for the HistoryItemD shape AND FULFILLED-PORT for
 *  the Phase 6 KMP-time port (kotlinx.datetime.LocalDateTime branch):
 *  verified `kotlinx.datetime.LocalDateTime` import (line 7); verified
 *  the default-arg `Clock.System.now().toLocalDateTime(TimeZone
 *  .currentSystemDefault())` on line 30 (lastReadDate); verified
 *  `@OptIn(ExperimentalTime::class)` opt-in (line 15); verified
 *  `java.time.LocalDateTime` is absent from imports + tree (grep
 *  `java.time.LocalDateTime` in :data/local/entity zero hits). The
 *  `LocalDateTimeConverter` Long epoch-millis wire-format serde path
 *  is the LIVE round-trip (cluster183 leaf 4 LocalDateTimeConverter
 *  Task #638) — verified by cross-reference against
 *  `@TypeConverters(...LocalDateTimeConverter::class...)` on sibling
 *  `MangaDatabase.kt` (cluster185 leaf 3, line 52).
 *
 *  (b) 6-entity-array LIVE membership for HistoryItemD — LIVE-NOT-STALE;
 *  HistoryItemD is the third member of the 6-entity array on `@Database`
 *  in sibling `MangaDatabase.kt` (cluster185 leaf 3/5, line 39). The
 *  `@Entity(tableName = "history_items")` (line 16) is the LIVE table
 *  reached by HistoryDao @Query bodies (cluster184 leaf 3 HistoryDao
 *  Task #639). No migration targets the `history_items` table — the
 *  table was created as part of the version-1 schema and has not been
 *  altered since (verified: no MIGRATION_X_Y body references "history_items").
 *
 *  (c) 14-column row shape (id + api + language + mangaId + mangaUrl +
 *  mangaTitle + mangaImageUrl + chapterUrl + chapterTitle + isDownloaded
 *  + localImagePaths + lastReadDate + lastReadPage + totalPages) —
 *  LIVE-NOT-STALE; 14 columns total (counting `id`). The naming convention
 *  "HistoryItemD" (D suffix vs the natural "HistoryItem") is the LIVE
 *  legacy entity-class name, tolerated per the broader convention of
 *  preserving legacy class names verbatim during the rework migration.
 *  The DAO/domain-model split puts the un-suffixed `HistoryItem` as the
 *  :domain/model class (cluster135 closing leaf 5/5 :domain/model
 *  history-tier sweep). The `localImagePaths: List<String>` field requires
 *  serde via `StringListConverter` (cluster183 leaf 5 StringListConverter
 *  Task #638), but unlike SavedMangaEntity this entity does NOT carry an
 *  explicit `@TypeConverters` annotation — converter resolution falls
 *  back to the `@TypeConverters` block on MangaDatabase itself (line 47-53
 *  of cluster185 leaf 3 MangaDatabase.kt).
 *
 * Verified: 1 entity declaration with 14 columns + 1 default Clock.System
 * .now().toLocalDateTime() expression + 1 @Entity annotation + 1
 * @OptIn(ExperimentalTime::class) opt-in. Sibling: SavedChapterEntity
 * (cluster186 prior sibling); ChapterNotification (cluster186 succeeding
 * sibling). LEAF 4/5 of the cluster186 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch. Compound classification:
 * LIVE-NOT-STALE + FULFILLED-PORT for the Phase 6 KMP-time port
 * (kotlinx.datetime.LocalDateTime branch). The "HistoryItemD" legacy
 * D-suffix naming preserved verbatim per the audit-trail-preservation
 * convention. Original Phase-6 migration-note prose preserved verbatim.
 */
