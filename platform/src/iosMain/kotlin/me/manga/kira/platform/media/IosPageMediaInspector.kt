package me.manga.kira.platform.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.Timeout
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedAlways
import platform.Foundation.NSLock
import platform.Foundation.create

/** ImageIO metadata/status plus a bounded validation thumbnail over the exact framed CFData. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPageMediaInspector(
    private val policy: PageInspectionPolicy = PageInspectionPolicy(),
    private val system: FileSystem = FileSystem.SYSTEM,
) : PageMediaInspector {
    override fun inspect(encoded: ByteArray): PageInspection =
        withPageProbeLock {
            encodedPageRejection(encoded.size.toLong(), policy)?.let { return@withPageProbeLock it }
            val data =
                encoded.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), encoded.size.toLong()) }
                    ?: return@withPageProbeLock PageInspection.ReadFailure(IOException("Could not retain encoded page snapshot"))
            inspectAndRelease(data)
        }

    override fun inspect(path: Path): PageInspection =
        withPageProbeLock {
            try {
                val metadata = system.metadata(path)
                if (!metadata.isRegularFile) throw IOException("Page is not a regular file")
                val size = metadata.size ?: throw IOException("Page size is unavailable")
                encodedPageRejection(size, policy) ?: inspectMappedFile(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                PageInspection.ReadFailure(failure)
            }
        }

    private fun inspectMappedFile(path: Path): PageInspection {
        // Mapping a private immutable file avoids a complete Kotlin ByteArray after streaming it.
        // Both framing and ImageIO use THIS retained mapping, not two opens of a mutable path.
        val mapped =
            NSData.create(contentsOfFile = path.toString(), options = NSDataReadingMappedAlways, error = null)
                ?: return PageInspection.ReadFailure(IOException("Could not map encoded page snapshot"))
        val data: CFDataRef =
            CFBridgingRetain(mapped)?.reinterpret()
                ?: return PageInspection.ReadFailure(IOException("Could not retain mapped page snapshot"))
        return inspectAndRelease(data)
    }

    private fun inspectAndRelease(data: CFDataRef): PageInspection =
        try {
            inspectPageInput(policy, CFDataGetLength(data), { CfPageSource(data) }) { format ->
                inspectIosPage(data, format, policy)
            }
        } finally {
            CFRelease(data)
        }
}

private val PAGE_PROBE_LOCK = NSLock()

private inline fun <T> withPageProbeLock(block: () -> T): T {
    PAGE_PROBE_LOCK.lock()
    return try {
        block()
    } finally {
        PAGE_PROBE_LOCK.unlock()
    }
}

/** Only small copies for the streaming framing/CRC pass; the native decoder shares the same CFData. */
@OptIn(ExperimentalForeignApi::class)
private class CfPageSource(
    private val data: CFDataRef,
) : Source {
    private val size = CFDataGetLength(data)
    private var position = 0L

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (position == size) return -1
        val count = minOf(byteCount, size - position, CF_READ_BYTES).toInt()
        val pointer = CFDataGetBytePtr(data)?.plus(position) ?: throw IOException("Page snapshot has no bytes")
        sink.write(pointer.readBytes(count))
        position += count
        return count.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit // The inspector owns/releases data, not this borrowed read cursor.
}

private const val CF_READ_BYTES: Long = 8192
