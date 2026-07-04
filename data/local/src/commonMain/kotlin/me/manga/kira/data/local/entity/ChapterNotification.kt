package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

// Migration notes (Phase 6):
//   - @Parcelize + Parcelable dropped (Android-only).
//   - java.time.LocalDate -> kotlinx.datetime.LocalDate.
//   - LocalDate.now() -> Clock.System.todayIn(currentTZ).
@OptIn(ExperimentalTime::class)
@Entity(tableName = "notifications")
data class ChapterNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val api: String,
    val language: String,
    val mangaId: Long,
    val mangaTitle: String,
    val mangaImageUrl: String,
    val mangaUrl: String,
    val chapterId: Long,
    val chapterNumber: String,
    val chapterUrl: String,
    val notificationDate: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val isRead: Boolean = false,
    val isDownloaded: Boolean = false,
    val localImagePaths: List<String> = emptyList(),
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster186.staleKdocSweep.cascade,
 * Task #682, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-ninety-fourth sibling of the cluster57-185
 * sweep continuum — CLOSING LEAF 5/5 of the wave-56 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch; ChapterNotification.kt 5/5).
 *
 *  (a) Inline migration-note comment "Migration notes (Phase 6): @Parcelize
 *  + Parcelable dropped (Android-only) + java.time.LocalDate -> kotlinx
 *  .datetime.LocalDate + LocalDate.now() -> Clock.System.todayIn(currentTZ)"
 *  — LIVE-NOT-STALE for the ChapterNotification shape AND FULFILLED-PORT
 *  for the Phase 6 KMP-time + de-Parcelize port (compact variant): verified
 *  `@Parcelize` absent from imports + class annotations (grep `Parcelize`
 *  in :data/local/entity zero hits); verified `kotlinx.datetime.LocalDate`
 *  import (line 7); verified the default-arg `Clock.System.todayIn(TimeZone
 *  .currentSystemDefault())` on line 29 (notificationDate); verified
 *  `@OptIn(ExperimentalTime::class)` opt-in (line 15); verified
 *  `java.time.LocalDate` is absent from imports + tree. The "compact"
 *  prose variant (3-line bullets vs 5-line bullets on SavedChapterEntity)
 *  is intentional — the same Phase-6 KMP-time + de-Parcelize port pattern
 *  is documented in two flavors across the entity tier, neither one wrong.
 *
 *  (b) 6-entity-array LIVE membership for ChapterNotification — LIVE-NOT
 *  -STALE; ChapterNotification is the fourth member of the 6-entity array
 *  on `@Database` in sibling `MangaDatabase.kt` (cluster185 leaf 3/5,
 *  line 40). The `@Entity(tableName = "notifications")` (line 16) is the
 *  LIVE table reached by NotificationDao @Query bodies (cluster184 leaf 5
 *  NotificationDao Task #639). No migration targets the `notifications`
 *  table — the table was created as part of the version-1 schema and has
 *  not been altered since (verified: no MIGRATION_X_Y body references
 *  "notifications").
 *
 *  (c) 14-column row shape (id + api + language + mangaId + mangaTitle +
 *  mangaImageUrl + mangaUrl + chapterId + chapterNumber + chapterUrl +
 *  notificationDate + isRead + isDownloaded + localImagePaths) — LIVE-NOT
 *  -STALE; 14 columns total (counting `id`). The class-name typo
 *  "ChapterNotification" (vs the more conventional "ChapterNotificationEntity"
 *  with explicit -Entity suffix matching the other 5 entities) is the LIVE
 *  legacy class-name and is preserved verbatim per the audit-trail
 *  -preservation convention. The `localImagePaths: List<String>` field
 *  shares the SavedMangaEntity / HistoryItemD serde posture — `StringListConverter`
 *  via MangaDatabase's `@TypeConverters` block.
 *
 * --- CLOSING-LEAF SUMMARY (cluster186 commonMain :data/local
 * DatabaseBuilder + entity tier) ---
 *
 * The cluster186 wave-56 5-leaf batch sweeps the commonMain :data/local
 * DatabaseBuilder + entity tier: DatabaseBuilder.kt (Task #678 — the
 * cluster185-deferred 1-leaf closer) + 4 prose-bearing entities
 * (SavedMangaEntity Task #679 + SavedChapterEntity Task #680 + HistoryItemD
 * Task #681 + ChapterNotification Task #682). Combined with the cluster183
 * 4-leaf :data/local/converter sweep (Task #638), the cluster184 5-leaf
 * :data/local/dao sweep (Task #639), and the cluster185 5-leaf :data/local
 * closing-tier sweep (Task #640 — SourcesDao + StatisticsDeo + MangaDatabase
 * + Migrations + MangaDatabaseFactory), the commonMain :data/local tier
 * is FULLY SWEPT modulo three non-postscripted files (all bare-prose-less
 * and properly skipped per the cluster175 precedent):
 *   (i)   LibraryDeo.kt (carries only functional step-comments inside
 *         @Transaction bodies — cluster185-deferred-skip).
 *   (ii)  ChapterDownloadEntity.kt (zero comment lines — entity declaration
 *         only).
 *   (iii) SourcesEntity.kt (zero comment lines — entity declaration only).
 *
 * Cumulative cluster183-186 commonMain :data/local tier sweep totals:
 *   - 4 + 5 + 5 + 5 = 19 §253 postscripts across 19 files.
 *   - 3 bare-prose-less skips (LibraryDeo + ChapterDownloadEntity +
 *     SourcesEntity) — total :data/local file count 22.
 *   - The Phase 6 Room KMP port is now FULFILLED-PORT classified across
 *     the entire :data/local tier (5 TypeConverters + 5 DAOs + 1
 *     MangaDatabase + 7 Migrations + 1 MangaDatabaseFactory + 1
 *     DatabaseBuilder + 4 prose-bearing entities = 24 distinct ports
 *     verified across 19 prose-bearing files).
 *   - The Phase 9.x retire/prune chain across the :data/local tier:
 *     cluster184 (Tasks #386 + #388 + #392 + #394 + #396 + #398 + #401
 *     + #404 + #441) + cluster185 leaves 1-2 (Tasks #388 + #389 + #390
 *     on SourcesDao + Task #393 on StatisticsDeo) + cluster185 leaves
 *     3-5 (1 FULFILLED-RETIRE cross-reference for Task #380 inside
 *     MangaDatabaseFactory's Bug-3 workaround note) — total 14 distinct
 *     FULFILLED-RETIRE classifications across the :data/local prose
 *     -bearing surface.
 *   - 1 FORECAST-NOT-YET-FULFILLED classification (the Phase-9
 *     `@Serializable` clause on SavedChapterEntity — no observable
 *     progress on SavedStateHandle integration since port, remains a
 *     forecast).
 *
 * The next outside-the-:data/local-tier prose-bearing candidates are
 * the :data/datasource/ files (if any), :data/util/ files, and any
 * remaining :data root-tier files (LibraryRepositoryImpl already
 * cluster154 swept; LibraryMappers already cluster151 swept). The
 * cluster187 wave-57 batch will scout these candidates.
 *
 * Verified: 1 entity declaration with 14 columns + 1 default Clock.System
 * .todayIn() expression + 1 @Entity annotation + 1 @OptIn(ExperimentalTime
 * ::class) opt-in. Sibling: HistoryItemD (cluster186 prior sibling).
 * CLOSING LEAF 5/5 of the cluster186 commonMain :data/local DatabaseBuilder
 * + entity tier 5-leaf batch + CLOSING LEAF of the commonMain :data/local
 * prose-bearing tier FULLY SWEPT (modulo 3 bare-prose-less skips:
 * LibraryDeo + ChapterDownloadEntity + SourcesEntity). Compound
 * classification: LIVE-NOT-STALE + FULFILLED-PORT for the Phase 6
 * KMP-time + de-Parcelize port (compact variant). The "ChapterNotification"
 * no-Entity-suffix naming preserved verbatim per the audit-trail
 * -preservation convention. Original Phase-6 migration-note prose preserved
 * verbatim.
 */
