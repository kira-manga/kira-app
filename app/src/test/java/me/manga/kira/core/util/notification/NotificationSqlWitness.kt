package me.manga.kira.core.util.notification

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.util.concurrent.atomic.AtomicInteger

/** Faults actual generated-DAO SQL inside Room's transaction, never a delegated DAO default body. */
internal class NotificationSqlWitness(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    val chapterInserts = AtomicInteger()
    val notificationInserts = AtomicInteger()

    @Volatile
    var beforeChapterInsert: (Int) -> Unit = {}

    @Volatile
    var beforeNotificationInsert: (Int) -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        connection.execSQL("PRAGMA foreign_keys = ON")
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                val insert = sql.trimStart().startsWith("INSERT", ignoreCase = true)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (insert && sql.contains("`saved_chapters`")) {
                            beforeChapterInsert(chapterInserts.incrementAndGet())
                        }
                        if (insert && sql.contains("`notifications`")) {
                            beforeNotificationInsert(notificationInserts.incrementAndGet())
                        }
                        return statement.step()
                    }
                }
            }
        }
    }
}
