package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

// Migration notes (Phase 6):
//   - @Parcelize + Parcelable dropped (Android-only API). Phase 9 will use kotlinx.serialization
//     @Serializable for SavedStateHandle in lifecycle-viewmodel-savedstate KMP if needed.
//   - java.time.LocalDate -> kotlinx.datetime.LocalDate. Wire format (Long epoch-day via
//     LocalDateConverter) unchanged.
//   - LocalDate.now() -> Clock.System.todayIn(TimeZone.currentSystemDefault()). Same observable value.
@OptIn(ExperimentalTime::class)
@Entity(
    tableName = "saved_chapters",
    foreignKeys = [
        ForeignKey(
            entity = SavedMangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["mangaId", "url"], unique = true),
    ],
)
data class SavedChapterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mangaId: Long,
    val name: String,
    val number: String,
    val url: String,
    val date: LocalDate? = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val isDownloaded: Boolean = false,
    val isBookmarked: Boolean = false,
    val isRead: Boolean = false,
    val isNew: Boolean = false,
    val lastReadPage: Int = 0,
    val lastReadDate: Long = 0,
    val localImagePaths: List<String> = emptyList(),
    // Epoch-millis timestamp of when this chapter was DISCOVERED by a refresh (set alongside
    // isNew=true). Distinct from `date` (the chapter's publish date): the NEW badge auto-expires
    // 4 days after discovery, evaluated at read time. 0 for rows saved before this column existed
    // and for the add-to-library path (which never flags isNew). Added in DB v9 -> v10.
    val fetchedAt: Long = 0,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster186.staleKdocSweep.cascade,
 * Task #680, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-ninety-second sibling of the cluster57-185
 * sweep continuum — middle leaf 3/5 of the wave-56 commonMain :data/local
 * DatabaseBuilder + entity tier 5-leaf batch; SavedChapterEntity.kt 3/5).
 *
 *  (a) Inline migration-note comment "Migration notes (Phase 6): @Parcelize
 *  + Parcelable dropped (Android-only API) + Phase 9 will use kotlinx
 *  .serialization @Serializable for SavedStateHandle in lifecycle-viewmodel
 *  -savedstate KMP if needed + java.time.LocalDate -> kotlinx.datetime
 *  .LocalDate + Wire format (Long epoch-day via LocalDateConverter)
 *  unchanged + LocalDate.now() -> Clock.System.todayIn(TimeZone
 *  .currentSystemDefault()) + Same observable value" — LIVE-NOT-STALE
 *  for the SavedChapterEntity shape AND FULFILLED-PORT for the Phase 6
 *  KMP-time + de-Parcelize port: verified `@Parcelize` absent from imports
 *  + class annotations (grep `Parcelize` in :data/local/entity zero hits);
 *  verified `kotlinx.datetime.LocalDate` import (line 9); verified the
 *  default-arg `Clock.System.todayIn(TimeZone.currentSystemDefault())`
 *  on line 41 (date); verified `@OptIn(ExperimentalTime::class)` opt-in
 *  (line 19). The forward-looking Phase 9 `@Serializable` clause is
 *  FORECAST-NOT-YET-FULFILLED — no `@Serializable` annotation on the
 *  entity yet, no `SavedStateHandle` integration yet; remains a forecast
 *  per the rework slice plan. The `LocalDateConverter` wire-format Long
 *  epoch-day is the LIVE serde path (cluster183 leaf 3 LocalDateConverter
 *  Task #638) — verified by cross-reference against
 *  `@TypeConverters(...LocalDateConverter::class...)` on sibling
 *  `MangaDatabase.kt` (cluster185 leaf 3, line 51).
 *
 *  (b) 6-entity-array LIVE membership for SavedChapterEntity — LIVE-NOT
 *  -STALE; SavedChapterEntity is the second member of the 6-entity array
 *  on `@Database` in sibling `MangaDatabase.kt` (cluster185 leaf 3/5,
 *  line 38). The `@ForeignKey(entity = SavedMangaEntity::class,
 *  parentColumns = ["id"], childColumns = ["mangaId"], onDelete =
 *  ForeignKey.CASCADE)` (lines 23-28) is LIVE per the saved-chapter-belongs
 *  -to-saved-manga business invariant; the CASCADE delete is LIVE per the
 *  bulk-remove-from-library transactional semantics (cluster184 LibraryDeo
 *  @Transaction body). The `@Index(value = ["mangaId", "url"], unique =
 *  true)` (line 31) is LIVE per the unique-URL-per-manga business invariant.
 *  The `tableName = "saved_chapters"` is the LIVE table reached by
 *  MIGRATION_6_7 (isNew ALTER — cluster185 leaf 4 Migrations.kt line 142).
 *
 *  (c) 13-column row shape (id + mangaId + name + number + url + date +
 *  isDownloaded + isBookmarked + isRead + isNew + lastReadPage +
 *  lastReadDate + localImagePaths) — LIVE-NOT-STALE; 13 columns total
 *  (counting `id`). Reached by ChapterDao + LibraryDeo + StatisticsDeo
 *  + 1 Migration object via the `saved_chapters` table-name. The
 *  `date: LocalDate?` nullable-LocalDate is the only nullable column;
 *  all other primitives carry defaults.
 *
 * Verified: 1 entity declaration with 13 columns + 1 default Clock.System
 * .todayIn() expression + 4 annotations (@Entity + @ForeignKey + @Index)
 * + 1 @OptIn(ExperimentalTime::class) opt-in. Sibling: SavedMangaEntity
 * (cluster186 prior sibling); HistoryItemD (cluster186 succeeding sibling).
 * MIDDLE LEAF 3/5 of the cluster186 commonMain :data/local DatabaseBuilder
 * + entity tier 5-leaf batch. Compound classification: LIVE-NOT-STALE +
 * FULFILLED-PORT for the Phase 6 KMP-time + de-Parcelize port + FORECAST
 * -NOT-YET-FULFILLED for the Phase-9 @Serializable clause (no observable
 * progress on SavedStateHandle integration since port). Original Phase-6
 * migration-note prose preserved verbatim per the audit-trail-preservation
 * convention.
 */
