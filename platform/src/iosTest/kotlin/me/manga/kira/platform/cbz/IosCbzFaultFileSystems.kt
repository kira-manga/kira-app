package me.manga.kira.platform.cbz

import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink

/** Real ZIP writes with one deliberate write/close fault, shared by the completeness cases. */
internal class IosCbzWriteFaultFileSystem(
    delegate: FileSystem,
    private val onClose: Boolean,
) : ForwardingFileSystem(delegate) {
    override fun sink(
        file: Path,
        mustCreate: Boolean,
    ): Sink = FaultSink(super.sink(file, mustCreate), onClose)

    private class FaultSink(
        private val delegate: Sink,
        private val onClose: Boolean,
    ) : Sink by delegate {
        override fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            if (!onClose) throw IOException("write denied")
            delegate.write(source, byteCount)
        }

        override fun close() {
            delegate.close()
            if (onClose) throw IOException("close failed")
        }
    }
}

/** Keeps the central directory readable but damages the first real generated entry's payload. */
internal class IosCbzCorruptingZipFileSystem(
    private val fixture: IosCbzTestFixture,
) : ForwardingFileSystem(fixture.system) {
    override fun sink(
        file: Path,
        mustCreate: Boolean,
    ): Sink {
        val delegate = super.sink(file, mustCreate)
        return object : Sink by delegate {
            override fun close() {
                delegate.close()
                val damaged = fixture.bytes(file)
                // STORE local header + the first generated name; leave the ZIP directory intact.
                val payloadStart = ZIP_LOCAL_HEADER_SIZE + "page_0000.webp".length
                damaged[payloadStart] = (damaged[payloadStart].toInt() xor 1).toByte()
                fixture.system.write(file) { write(damaged) }
            }
        }
    }

    private companion object {
        const val ZIP_LOCAL_HEADER_SIZE = 30
    }
}
