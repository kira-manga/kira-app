package me.manga.kira.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Fresh-create and migration parity, without open-time allocator repair or catalog selection. */
object EffectiveSourceSelectionSchema : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        installAndSeed(connection)
    }

    internal fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE `effective_source_selection` (
                `id` INTEGER NOT NULL,
                `generation` INTEGER NOT NULL,
                `payload` TEXT NOT NULL,
                `payloadDigest` TEXT NOT NULL,
                PRIMARY KEY (`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `source_selection_generation` (
                `id` INTEGER NOT NULL,
                `nextGeneration` INTEGER NOT NULL,
                PRIMARY KEY (`id`)
            )
            """.trimIndent(),
        )
        installAndSeed(connection)
    }

    private fun installAndSeed(connection: SQLiteConnection) {
        validationTriggers(connection, "effective_source_selection", invalidSelection)
        validationTriggers(connection, "source_selection_generation", invalidAllocator)
        connection.execSQL("INSERT INTO `source_selection_generation` (`id`, `nextGeneration`) VALUES (0, 1)")
    }

    private fun validationTriggers(connection: SQLiteConnection, table: String, invalid: String) {
        listOf("INSERT", "UPDATE").forEach { operation ->
            connection.execSQL(
                """
                CREATE TRIGGER `${table}_validate_${operation.lowercase()}`
                BEFORE $operation ON `$table` WHEN $invalid
                BEGIN SELECT RAISE(ABORT, 'Invalid effective source selection'); END
                """.trimIndent(),
            )
        }
    }

    private val invalidSelection =
        """
        typeof(NEW.id) <> 'integer' OR NEW.id <> 0
        OR typeof(NEW.generation) <> 'integer' OR NEW.generation <= 0
        OR typeof(NEW.payload) <> 'text'
        OR typeof(NEW.payloadDigest) <> 'text'
        OR length(NEW.payloadDigest) <> 64
        OR length(CAST(NEW.payloadDigest AS BLOB)) <> 64
        OR NEW.payloadDigest GLOB '*[^0-9a-f]*'
        """.trimIndent()

    private val invalidAllocator =
        """
        typeof(NEW.id) <> 'integer' OR NEW.id <> 0
        OR typeof(NEW.nextGeneration) <> 'integer' OR NEW.nextGeneration <= 0
        """.trimIndent()
}
