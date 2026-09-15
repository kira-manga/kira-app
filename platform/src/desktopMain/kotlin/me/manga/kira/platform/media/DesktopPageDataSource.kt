package me.manga.kira.platform.media

import okio.Buffer
import okio.Source
import okio.Timeout
import org.jetbrains.skia.Data

/** A borrowed native snapshot cursor: no full encoded-array or ImageIO memory-cache copy. */
internal class DesktopPageDataSource(
    private val data: Data,
) : Source {
    private var position = 0

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        return when {
            byteCount == 0L -> 0L
            position == data.size -> -1L
            else -> {
                val count = minOf(byteCount, (data.size - position).toLong(), PAGE_READ_BYTES.toLong()).toInt()
                sink.write(data.getBytes(position, count))
                position += count
                count.toLong()
            }
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}

private const val PAGE_READ_BYTES: Int = 8192
