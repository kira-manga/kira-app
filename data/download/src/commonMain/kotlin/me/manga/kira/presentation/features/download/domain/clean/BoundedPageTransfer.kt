package me.manga.kira.presentation.features.download.domain.clean

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.media.PageBytePolicy
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import kotlin.random.Random

internal fun requireUncachedPageClient(client: HttpClient) {
    // HttpCache buffers a whole response before our scoped streaming consumer can enforce a limit.
    require(client.pluginOrNull(HttpCache) == null) { "Page downloads require an uncached HTTP client" }
}

internal fun pageTemporaryPath(
    directory: Path,
    pageIndex: Int,
): Path = directory / ".image_$pageIndex-${Random.nextLong().toULong().toString(TEMPORARY_NAME_RADIX)}.partial"

/** Owns a newly created temporary file; never touches the currently published image. */
internal suspend fun transferPageBody(
    channel: ByteReadChannel,
    declaredLength: Long?,
    system: FileSystem,
    temporary: Path,
    policy: PageBytePolicy,
): Long {
    var created = false
    // Keep every failure, including cancellation, until channel and owned-file cleanup have run.
    val transfer =
        runCatching {
            policy.checkDeclaredLength(declaredLength)
            val output = system.sink(temporary, mustCreate = true)
            created = true
            val count =
                output.buffer().use { sink ->
                    readBoundedPage(channel, policy) { bytes, size -> sink.write(bytes, 0, size) }
                }
            policy.checkFileSize(system.metadata(temporary).size)
            currentCoroutineContext().ensureActive()
            count
        }
    val closeFailure = runCatching { channel.cancel() }.exceptionOrNull()
    val deleteFailure =
        if (created && (transfer.isFailure || closeFailure != null)) {
            runCatching { system.delete(temporary, mustExist = false) }.exceptionOrNull()
        } else {
            null
        }
    val failure = transfer.exceptionOrNull() ?: closeFailure ?: deleteFailure
    if (failure != null) {
        if (closeFailure != null && closeFailure !== failure) failure.addSuppressed(closeFailure)
        if (deleteFailure != null && deleteFailure !== failure) failure.addSuppressed(deleteFailure)
        throw failure
    }
    return transfer.getOrThrow()
}

private suspend fun readBoundedPage(
    channel: ByteReadChannel,
    policy: PageBytePolicy,
    write: (ByteArray, Int) -> Unit,
): Long {
    val buffer = ByteArray(PAGE_TRANSFER_CHUNK_BYTES)
    var written = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        // Read at most one byte beyond the ceiling; reject that byte BEFORE writing any of its chunk.
        val request = minOf(buffer.size.toLong(), policy.maxEncodedBytes - written + 1).toInt()
        val count = channel.readAvailable(buffer, 0, request)
        if (count == -1) break
        if (count == 0) throw IOException("Page body made no read progress")
        written = policy.checkedTotal(written, count)
        write(buffer, count)
    }
    channel.closedCause?.let { throw it }
    policy.checkFileSize(written)
    return written
}

private const val PAGE_TRANSFER_CHUNK_BYTES = 8192
private const val TEMPORARY_NAME_RADIX = 16
