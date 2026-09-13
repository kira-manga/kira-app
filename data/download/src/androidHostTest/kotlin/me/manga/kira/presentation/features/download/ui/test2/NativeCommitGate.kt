package me.manga.kira.presentation.features.download.ui.test2

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CompletableDeferred
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertSame

/** Blocks only after the real native outer commit has succeeded, before Room sees step's return. */
internal class NativeCommitGate {
    val target = AtomicReference<ChapterDownloadEntity?>()
    val writer = AtomicReference<SQLiteConnection?>()
    val nativeCommitted = CompletableDeferred<Unit>()
    val hits = AtomicInteger()
    private val released = CountDownLatch(1)

    fun arm(expected: ChapterDownloadEntity) {
        check(target.compareAndSet(null, expected))
    }

    fun disarm() {
        target.set(null)
    }

    fun noteWriter(connection: SQLiteConnection) {
        if (!writer.compareAndSet(null, connection)) assertSame(writer.get(), connection)
    }

    fun afterNativeCommit(
        connection: SQLiteConnection,
        sql: String,
    ) {
        assertSame(writer.get(), connection)
        check(hits.incrementAndGet() == 1) { "Ambiguous terminal commit gate" }
        println("APP75 native-writer=${System.identityHashCode(connection)} committed-outer-sql=$sql hits=1")
        nativeCommitted.complete(Unit)
        check(released.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Native commit gate timed out" }
    }

    fun release() {
        released.countDown()
    }
}

/** No driver implementation is substituted: open/prepare/step and all other calls are forwarded. */
internal class CommitObservingDriver(
    private val real: SQLiteDriver,
    private val gate: NativeCommitGate,
) : SQLiteDriver by real {
    override fun open(fileName: String): SQLiteConnection = CommitObservedConnection(real.open(fileName), gate)
}

private class CommitObservedConnection(
    private val real: SQLiteConnection,
    private val gate: NativeCommitGate,
) : SQLiteConnection by real {
    private var outerTransaction = false
    private var savepoints = 0
    private var ledgerWritten = false
    private var savedWritten = false
    private var rolledBack = false

    override fun prepare(sql: String): SQLiteStatement = CommitObservedStatement(real.prepare(sql), sql, this)

    fun afterSuccessfulStep(
        sql: String,
        bindings: TerminalBindings,
    ) {
        when {
            sql.startsWith("BEGIN") -> begin()
            sql.startsWith("SAVEPOINT ") -> savepoints++
            sql.startsWith("RELEASE ") -> savepoints--
            sql.startsWith("ROLLBACK TO ") -> rolledBack = true
            sql.startsWith("ROLLBACK") -> outerTransaction = false
            sql in OUTER_COMMIT_SQL -> finish(sql)
            outerTransaction && sql == LEDGER_COMPLETION_SQL -> observeLedger(bindings)
            outerTransaction && sql == SAVED_COMPLETION_SQL -> observeSaved(bindings)
        }
    }

    private fun begin() {
        check(!outerTransaction)
        outerTransaction = true
        savepoints = 0
        ledgerWritten = false
        savedWritten = false
        rolledBack = false
    }

    private fun observeLedger(bindings: TerminalBindings) {
        val expected = gate.target.get() ?: return
        if (bindings.matchesLedger(expected)) {
            check(!ledgerWritten)
            gate.noteWriter(real)
            ledgerWritten = true
            println("APP75 native-terminal-write=ledger id=${expected.id} chapter=${expected.chapterId}")
        }
    }

    private fun observeSaved(bindings: TerminalBindings) {
        val expected = gate.target.get() ?: return
        if (ledgerWritten && bindings.matchesSaved(expected)) {
            check(!savedWritten)
            gate.noteWriter(real)
            savedWritten = true
            println("APP75 native-terminal-write=saved chapter=${expected.chapterId}")
        }
    }

    private fun finish(sql: String) {
        val bothWrites = ledgerWritten && savedWritten && !rolledBack
        val matched = outerTransaction && savepoints == 0 && bothWrites
        outerTransaction = false
        if (matched) gate.afterNativeCommit(real, sql)
    }
}

private class CommitObservedStatement(
    private val real: SQLiteStatement,
    sql: String,
    private val connection: CommitObservedConnection,
) : SQLiteStatement by real {
    private val normalizedSql =
        sql
            .replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd(';')
            .uppercase(Locale.ROOT)
    private val bindings = TerminalBindings()

    override fun bindLong(
        index: Int,
        value: Long,
    ) {
        real.bindLong(index, value)
        bindings.numbers[index] = value
    }

    override fun bindText(
        index: Int,
        value: String,
    ) {
        real.bindText(index, value)
        bindings.strings[index] = value
    }

    override fun clearBindings() {
        real.clearBindings()
        bindings.numbers.clear()
        bindings.strings.clear()
    }

    override fun step(): Boolean {
        val result = real.step()
        // A failed native step never reaches the observer, and its real exception is not caught.
        connection.afterSuccessfulStep(normalizedSql, bindings)
        return result
    }
}

private class TerminalBindings {
    val numbers = mutableMapOf<Int, Long>()
    val strings = mutableMapOf<Int, String>()

    fun matchesLedger(expected: ChapterDownloadEntity): Boolean =
        strings[LEDGER_STATE_BIND] == "SUCCESS" &&
            numbers[LEDGER_ID_BIND] == expected.id &&
            numbers[LEDGER_CHAPTER_BIND] == expected.chapterId

    fun matchesSaved(expected: ChapterDownloadEntity): Boolean =
        numbers[SAVED_CHAPTER_BIND] == expected.chapterId &&
            numbers[SAVED_MANGA_BIND] == expected.mangaId &&
            strings[SAVED_URL_BIND] == expected.url
}

private const val LEDGER_STATE_BIND = 1
private const val LEDGER_ID_BIND = 3
private const val LEDGER_CHAPTER_BIND = 4
private const val SAVED_CHAPTER_BIND = 1
private const val SAVED_MANGA_BIND = 2
private const val SAVED_URL_BIND = 3
private val OUTER_COMMIT_SQL = setOf("END", "END TRANSACTION", "COMMIT", "COMMIT TRANSACTION")
private const val LEDGER_COMPLETION_SQL =
    "UPDATE CHAPTER_DOWNLOADS SET STATE = ?, PROGRESS = 100, SIZEBYTES = ?, ERRORMSG = NULL " +
        "WHERE ID = ? AND CHAPTERID = ?"
private const val SAVED_COMPLETION_SQL =
    "UPDATE SAVED_CHAPTERS SET ISDOWNLOADED = 1 WHERE ID = ? AND MANGAID = ? AND URL = ?"
