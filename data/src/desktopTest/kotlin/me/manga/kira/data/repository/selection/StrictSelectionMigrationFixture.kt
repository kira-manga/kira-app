package me.manga.kira.data.repository.selection

import androidx.room.Room
import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.local.EffectiveSourceSelectionSchema
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.entity.ReaderLegacyCapture
import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.ReaderLegacyDisposition
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.libraryDownload
import me.manga.kira.data.repository.libraryHistory
import me.manga.kira.data.repository.libraryNotification
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import me.manga.kira.sources.contracts.SourceSelectionToken
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * File-backed generated Room DAOs and the real immediate writer. No worker/native runtime exists in
 * this fixture; explicit candidate data is not production verification, readiness or gate authority.
 */
internal class StrictSelectionMigrationFixture : Closeable {
    private val directory = Files.createTempDirectory("kira-strict-selection-").toFile()
    private val file = File(directory, "selection.db")
    private var opened: MangaDatabase? = null
    val db: MangaDatabase get() = opened ?: openDatabase().also { opened = it }

    fun artifactFile(name: String): File = File(directory, name)

    fun reopen() {
        opened?.close()
        opened = null
    }

    override fun close() {
        reopen()
        check(directory.deleteRecursively())
    }

    suspend fun migrate(
        rules: List<AcceptedSourceAliasRule> = listOf(strictRule()),
        progress: SelectionPlanningProgress = SelectionPlanningProgress(),
        onWriterEntered: ((Job) -> Unit)? = null,
    ) {
        RoomMangaWriteTransaction(db).write {
            onWriterEntered?.invoke(currentCoroutineContext().job)
            StrictSourceSelectionMigration(db.sourceSelectionMigrationDao(), db.readerProgressDao())
                .migrateInTransaction(strictToken(), rules, progress)
        }
    }

    suspend fun execute(sql: String) {
        db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
    }

    suspend fun number(sql: String): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared(sql) {
                check(it.step())
                it.getLong(0)
            }
        }

    /** All ownership/artifact columns and original receipts, not a URL-only assertion. */
    suspend fun snapshot(): Map<String, StrictTableImage> =
        db.useReaderConnection { connection ->
            connection.deferredTransaction {
                STRICT_TABLES.mapValues { (table, order) ->
                    connection.usePrepared("SELECT * FROM $table ORDER BY $order") { statement ->
                        val columns = List(statement.getColumnCount()) { statement.getColumnName(it) }
                        val rows =
                            buildList {
                                while (statement.step()) {
                                    add(
                                        columns.indices.map {
                                            if (statement.isNull(it)) null else statement.getText(it)
                                        },
                                    )
                                }
                            }
                        StrictTableImage(columns, rows)
                    }
                }
            }
        }

    /** Exactly 2F + 5FC ownership rows; all copies retain meaningful template metadata/epochs. */
    suspend fun seedHighCardinality() {
        val first = saved("$STRICT_OLD/w/1", "$STRICT_OLD/c/1")
        related(first, DownloadingState.QUEUED)
        anchor(first.work.url, first.chapter.url)
        val parent = "((n - 1) / $STRICT_MANY_CHILDREN + 1)"
        val work = "'$STRICT_OLD/w/' || $parent"
        val chapter = "'$STRICT_OLD/c/' || ((n - 1) % $STRICT_MANY_CHILDREN + 1)"
        val linked = "CASE WHEN n % 2 = 0 THEN 0 ELSE $parent END"
        val count = STRICT_MANY_FAMILIES * STRICT_MANY_CHILDREN
        copyRows("saved_manga", "id", STRICT_MANY_FAMILIES, mapOf("id" to "n", "url" to "'$STRICT_OLD/w/' || n"))
        val readerWork = mapOf("workId" to "n", "workUrl" to "'$STRICT_OLD/w/' || n")
        copyRows("reader_work_state", "workId", STRICT_MANY_FAMILIES, readerWork)
        copyRows("saved_chapters", "id", count, mapOf("id" to "n", "mangaId" to parent, "url" to chapter))
        val readerChapter = mapOf("chapterId" to "n", "workId" to parent, "chapterUrl" to chapter)
        copyRows("reader_chapter_state", "chapterId", count, readerChapter)
        val download = mapOf("id" to "n", "chapterId" to "n", "mangaId" to parent, "url" to chapter)
        copyRows("chapter_downloads", "id", count, download)
        val history = mapOf("id" to "n", "mangaId" to linked, "mangaUrl" to work, "chapterUrl" to chapter)
        copyRows("history_items", "id", count, history)
        copyRows(
            "notifications",
            "id",
            count,
            mapOf("id" to "n", "mangaId" to linked, "chapterId" to "n", "mangaUrl" to work, "chapterUrl" to chapter),
        )
    }

    /** Clone one real entity row in SQL; no hand-maintained duplicate schema or 86k DAO seed calls. */
    private suspend fun copyRows(
        table: String,
        id: String,
        last: Int,
        replacements: Map<String, String>,
    ) {
        val columns =
            db.useReaderConnection { connection ->
                connection.usePrepared("SELECT * FROM $table LIMIT 0") { statement ->
                    List(statement.getColumnCount()) { statement.getColumnName(it) }
                }
            }
        val names = columns.joinToString(",") { "`$it`" }
        val values = columns.joinToString(",") { replacements[it] ?: "sample.`$it`" }
        execute(
            "WITH RECURSIVE seq(n) AS (SELECT 2 UNION ALL SELECT n + 1 FROM seq WHERE n < $last) " +
                "INSERT INTO $table($names) SELECT $values FROM $table AS sample CROSS JOIN seq WHERE sample.`$id` = 1",
        )
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = file.absolutePath)
            .addCallback(ReaderProgressConstraints)
            .addCallback(EffectiveSourceSelectionSchema)
            .setDriver(StrictSelectionDriver(BundledSQLiteDriver()))
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
}

private class StrictSelectionDriver(
    private val delegate: SQLiteDriver,
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection =
        delegate.open(fileName).also {
            it.execSQL("PRAGMA foreign_keys = ON")
            it.prepare("PRAGMA foreign_keys").use { statement -> check(statement.step() && statement.getLong(0) == 1L) }
        }
}

internal data class StrictTableImage(
    val columns: List<String>,
    val rows: List<List<String?>>,
)

internal data class StrictSavedFamily(
    val work: SavedMangaEntity,
    val chapter: SavedChapterEntity,
)

internal suspend fun StrictSelectionMigrationFixture.saved(
    workUrl: String = "$STRICT_OLD/work",
    chapterUrl: String = "$STRICT_OLD/chapter",
    api: String = STRICT_API,
): StrictSavedFamily {
    val draft = libraryParent(workUrl, api)
    val parent = draft.copy(id = db.backupDao().insertMangaRow(draft))
    check(parent.id > 0)
    val child = librarySavedChapter(parent, chapterUrl)
    val chapter = child.copy(id = db.backupDao().insertChapterRow(child))
    check(chapter.id > 0)
    return StrictSavedFamily(parent, chapter)
}

internal suspend fun StrictSelectionMigrationFixture.related(
    family: StrictSavedFamily,
    state: DownloadingState = DownloadingState.SUCCESS,
) {
    db.chapterDownloadingDao().insert(
        libraryDownload(family.work, family.chapter, state).copy(errorMsg = "retained error"),
    )
    db.backupDao().insertHistoryRow(libraryHistory(family.work, linkedId = 0).copy(chapterUrl = family.chapter.url))
    db.notificationDao().insertNotificationsList(listOf(libraryNotification(family.work, family.chapter)))
}

internal suspend fun StrictSelectionMigrationFixture.anchor(
    workUrl: String = "$STRICT_OLD/work",
    chapterUrl: String = "$STRICT_OLD/chapter",
    api: String = STRICT_API,
) {
    val dao = db.readerProgressDao()
    val original = dao.ensureSnapshot(api, workUrl, chapterUrl)
    val receipt =
        ReaderLegacyCleanupEntity(
            "$chapterUrl#legacy",
            "$workUrl|$chapterUrl|7",
            original.chapterId,
            ReaderLegacyCapture(original.workGeneration, original.chapterGeneration, ReaderLegacyDisposition.COPIED),
        )
    db.readerLegacyCleanupDao().recordOnce(receipt)
    dao.clearWork(api, workUrl)
    dao.clearChapter(api, workUrl, chapterUrl)
    check(dao.savePosition(dao.ensureSnapshot(api, workUrl, chapterUrl), STRICT_SAVED_PAGE))
}

internal fun strictToken(): SourceSelectionToken =
    SourceSelectionToken(
        2,
        SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, 1, "a".repeat(STRICT_DIGEST_LENGTH)),
        "b".repeat(STRICT_DIGEST_LENGTH),
    )

internal fun strictRule(
    api: String = STRICT_API,
    current: String = STRICT_NEW,
    previous: String = "old.test",
    authority: PreviousHostAuthority = PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
): AcceptedSourceAliasRule {
    val proof =
        if (authority == PreviousHostAuthority.UNKNOWN) {
            null
        } else {
            SourceSelectionProofRef(
                SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, 0, "c".repeat(STRICT_DIGEST_LENGTH)),
                api,
                null,
            )
        }
    return AcceptedSourceAliasRule(api, current, listOf(SelectedPreviousHost(previous, authority, proof)))
}

internal const val STRICT_API = "source"
internal const val STRICT_OLD = "https://old.test"
internal const val STRICT_NEW = "https://new.test"
internal const val STRICT_SAVED_PAGE = 9
internal const val STRICT_MANY_FAMILIES = 2_048
internal const val STRICT_MANY_CHILDREN = 8
internal const val STRICT_MANY_ROWS = 2 * STRICT_MANY_FAMILIES + 5 * STRICT_MANY_FAMILIES * STRICT_MANY_CHILDREN

private const val STRICT_DIGEST_LENGTH = 64

private val STRICT_TABLES =
    linkedMapOf(
        "saved_manga" to "id",
        "saved_chapters" to "id",
        "reader_work_state" to "workId",
        "reader_chapter_state" to "chapterId",
        "chapter_downloads" to "id",
        "history_items" to "id",
        "notifications" to "id",
        "chapter_artifacts" to "chapterId",
        "reader_legacy_cleanup" to "legacyKey, capturedPayload",
    )
