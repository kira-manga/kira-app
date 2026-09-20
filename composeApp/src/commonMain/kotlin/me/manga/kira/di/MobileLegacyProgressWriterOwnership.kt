package me.manga.kira.di

import me.manga.kira.core.platform.backupPlatformName
import me.manga.kira.data.repository.progress.LegacyProgressWriterOwnership

/**
 * The mobile binary has retired every URL-only progress writer: Reader and backup now write
 * scoped Room state. Its ordinary kira_settings store is private to the one application process
 * (no Android remote process/shared UID or Apple app-group/extension writer).
 *
 * That retirement, NOT merely a mutex, permits legacy cleanup. LegacyProgressSettings invokes
 * this only while holding the graph's shared Settings gate, then rereads the complete payload
 * before removal. Durable cleanup receipts are still retained for reopening/reconciliation.
 * If a legacy writer or shared-store process is introduced, this authority must be revoked or
 * replaced with actual exclusion. No physical-flush acknowledgement is implied.
 *
 * A second, older Desktop process can share its preferences node; deny there and preserve data.
 */
internal class MobileLegacyProgressWriterOwnership : LegacyProgressWriterOwnership {
    override suspend fun <T : Any> withExclusiveWriterOwnership(block: () -> T): T? =
        when (backupPlatformName()) {
            "android", "ios" -> block()
            else -> null
        }
}
