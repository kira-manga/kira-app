package me.manga.kira.core.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import me.manga.kira.platform.backup.BackupImportStaging
import okio.IOException
import okio.source
import kotlin.coroutines.cancellation.CancellationException

/** Provider SIZE is only an early hint: absent, unknown and lying values still use counted I/O. */
internal fun acquireAndroidBackup(
    context: Context,
    uri: Uri,
    staging: BackupImportStaging,
    checkpoint: () -> Unit,
): String =
    try {
        checkpoint()
        staging.stage(declaredSize(context, uri), checkpoint) {
            context.contentResolver.openInputStream(uri)?.source()
                ?: throw IOException("Backup provider did not open a stream")
        }
    } catch (denied: SecurityException) {
        throw IOException("Backup provider access denied", denied)
    }

private fun declaredSize(context: Context, uri: Uri): Long? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || !cursor.moveToFirst() || cursor.isNull(column)) null else cursor.getLong(column)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Some document providers do not implement metadata queries. The bounded stream remains
        // authoritative; inability to obtain an optional size is not proof of a readable file.
        null
    }
