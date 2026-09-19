package me.manga.kira.data.local

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Owns one Room writer transaction. Exceptions must escape [write] to roll back all statements;
 * nested DAO transactions use this same connection. No network or file work belongs in [block].
 */
interface MangaWriteTransaction {
    suspend fun <T> write(block: suspend () -> T): T
}

/** Production writer boundary, kept in the Room-owning module rather than exporting Room to data. */
class RoomMangaWriteTransaction(
    private val database: MangaDatabase,
) : MangaWriteTransaction {
    override suspend fun <T> write(block: suspend () -> T): T =
        withMangaWriteCancellation {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    ensureMangaWriteActive()
                    val result = block()
                    // An observed cancellation must escape inside the transaction, not at its return.
                    // Cancellation racing the subsequent commit is not an atomic rollback guarantee.
                    ensureMangaWriteActive()
                    result
                }
            }
        }
}
