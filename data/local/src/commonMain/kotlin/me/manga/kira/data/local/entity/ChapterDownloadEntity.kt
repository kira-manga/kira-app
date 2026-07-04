package me.manga.kira.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.manga.kira.presentation.features.download.data.DownloadingState

@Entity(
    tableName = "chapter_downloads",
    // CASCADE FK to saved_manga (belt-and-braces with the explicit purge in
    // LibraryDeo.removeMangaWithChapters) so a download-queue row can never outlive its manga and
    // resurface a stale "downloaded" badge/size. Mirrors SavedChapterEntity's saved_manga FK.
    foreignKeys = [
        ForeignKey(
            entity = SavedMangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapterId"], unique = true),
        Index(value = ["mangaId"]), // Room requires an index on FK child columns.
    ],
)
data class ChapterDownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: String,
    val chapterId: Long,
    val mangaId: Long,
    val api: String,
    val mangaTitle: String? = null,
    val url: String,
    val state: DownloadingState,
    val progress: Int,
    val errorMsg: String? = null,
    // Final on-disk size of the downloaded chapter in bytes, captured once at SUCCESS by walking
    // the chapter directory (the .cbz when CBZ archiving ran, else the loose pages). 0 until the
    // download completes (and 0 for rows migrated up from schema v8, which had no size column —
    // those are back-filled on the next launch by the startup reconcile). Native shows the chapter
    // size next to the date on the Library/Details chapter row; storing it avoids native's
    // per-composition directory walk. Added in DB v8 -> v9 (MIGRATION_8_9).
    val sizeBytes: Long = 0,
)

/*
 * §253 audit-trail postscript — cluster280 §253 sweep (2026-05-29)
 * ------------------------------------------------------------------
 * Classification: LIVE / LEGACY.
 *
 * LIVE evidence: registered as a database entity at MangaDatabase.kt:41
 * ("ChapterDownloadEntity::class," inside the @Database entities array, lines
 * 36-43) — one of the 6 entities the schema carries at version 8. The rework
 * consumes it through the :data strangler-fig mapper: DownloadsMappers.kt:61
 * ("internal fun ChapterDownloadEntity.toDomain(): DownloadedChapter = ...")
 * projects each persisted row into the rework :domain DownloadedChapter model,
 * driving DownloadsRepositoryImpl.kt's emission stream. The legacy producers
 * are also LIVE: HandelDataClasses.kt:250 (SavedChapterEntity.toChapterDownloadEntity)
 * + :268 (toChapterDownloadEntities) construct rows that the download repos
 * (DownloadRepositoryImpl.kt:78, CoroutineDownloadRepositoryImpl.kt:144) insert
 * via ChapterDownloadDao.insert. The owning DAO is bound per-platform at
 * PlatformModule.android.kt:89 ("single<ChapterDownloadDao> { get<MangaDatabase>().chapterDownloadingDao() }").
 * ARCHITECTURE.md:38489 corroborates: "ChapterDownloadEntity | 10 | :shared/data/local/entity | LIVE".
 *
 * LEGACY status: pre-rework :shared/commonMain Room entity. NOT a Phase-5.x
 * platform facade — no expect/actual fan exists; the rework deliberately maps
 * AWAY from this entity into the pure :domain DownloadedChapter rather than
 * exposing the Room row upward (DownloadsMappers.kt:16 "mutations go through
 * legacy"). cluster186 (DatabaseBuilder + entity tier, Task #641) and
 * ChapterNotification.kt:94 flagged this entity as a "zero comment lines"
 * prose-less skip; cluster280 closes that gap.
 *
 * Delta-axes:
 *  1. Platform API: androidx.room (KMP) — @Entity(tableName="chapter_downloads",
 *     indices=[Index(value=["chapterId"], unique=true)]), @PrimaryKey(autoGenerate=true).
 *     Pure commonMain; identical compile on all three targets.
 *  2. Threading/dispatcher: none owned by the entity (a plain data class). Read
 *     and write threading is the ChapterDownloadDao's concern.
 *  3. Error handling: the "state" column is a DownloadingState enum persisted via
 *     DownloadingStateConverter (this cluster's sibling) — an unknown name on
 *     read throws from valueOf. The nullable errorMsg / mangaTitle columns carry
 *     no constraint; failure detail is data, not exception, here.
 *  4. DI binding mechanism: entity is bound implicitly by membership in the
 *     @Database entities array (MangaDatabase.kt:41), not by a Koin single. Its
 *     DAO (ChapterDownloadDao) is the Koin-provided single.
 *  5. Schema stability: 10-column row; database-migration-report.md:32 records
 *     "ChapterDownloadEntity | no changes | Yes | migrated" — the port preserved
 *     the legacy shape verbatim, so the unique chapterId index and autoGenerate
 *     id remain the on-disk contract. The two removed PagingSource DAO methods
 *     (database-migration-report.md:55) did NOT alter this entity's columns.
 *
 * Nested-comment hazard check: zero legitimate KDoc/comment openers exist in the
 * original file body (bare entity declaration, no doc comments anywhere). This
 * appended block is balanced — exactly one opener and one closer, with no
 * interior comment delimiters in the prose.
 */
