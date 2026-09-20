package me.manga.kira.data.repository.progress

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import me.manga.kira.data.local.MIGRATION_16_17
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.LibraryControlledSnapshots
import me.manga.kira.data.repository.LibraryObservedTransactions
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.libraryPolicy

/** Real file-backed Room and production writer/guards; Settings remains an explicitly limited fake. */
internal class ProgressRuntimeFixture : Closeable {
    private val directory = Files.createTempDirectory("kira-progress-runtime-").toFile()
    private val file = File(directory, "runtime.db")
    private var opened: ProgressRuntimeComponents? = null
    val settings = ProgressTestSettings()
    val ownership = ProgressTestWriterOwnership()
    val gate = LegacyProgressSettingsGate()
    val legacySettings = LegacyProgressSettings(settings, gate, ownership)
    private val components: ProgressRuntimeComponents
        get() = opened ?: ProgressRuntimeComponents(openDatabase(), legacySettings).also { opened = it }
    val db get() = components.db
    val transactions get() = components.transactions
    val snapshots get() = components.snapshots
    val storage get() = components.storage
    val owners get() = components.owners
    val native get() = components.native
    val legacy get() = components.legacy

    fun reopen() {
        opened?.db?.close()
        opened = null
    }

    override fun close() {
        reopen()
        check(directory.deleteRecursively())
    }

    suspend fun parent(draft: SavedMangaEntity = libraryParent()): SavedMangaEntity {
        val id = db.backupDao().insertMangaRow(draft)
        check(id > 0)
        return draft.copy(id = id)
    }

    suspend fun chapter(draft: SavedChapterEntity): SavedChapterEntity {
        val id = db.backupDao().insertChapterRow(draft)
        check(id > 0)
        return draft.copy(id = id)
    }

    suspend fun execute(sql: String) {
        db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
    }

    private fun openDatabase(): MangaDatabase = Room.databaseBuilder<MangaDatabase>(name = file.absolutePath)
        .addMigrations(MIGRATION_16_17)
        .addCallback(ReaderProgressConstraints)
        .setDriver(ProgressRuntimeForeignKeysDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}

private class ProgressRuntimeComponents(val db: MangaDatabase, settings: LegacyProgressSettings) {
    val transactions = LibraryObservedTransactions(RoomMangaWriteTransaction(db))
    val snapshots = LibraryControlledSnapshots(transactions, libraryPolicy())
    val storage = ProgressStorage(
        db.readerProgressDao(), db.readerLegacyCleanupDao(), db.mangaDao(), db.chapterDao(), db.backupDao(),
    )
    val owners = ProgressOwnerTransactions(transactions, snapshots, storage)
    val native = RoomScopedReadProgressRepository(owners)
    val legacy = RoomLegacyReadProgressRepository(owners, settings)
}

private class ProgressRuntimeForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName).also { connection ->
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.prepare("PRAGMA foreign_keys").use { statement ->
            check(statement.step() && statement.getLong(0) == 1L)
        }
    }
}
