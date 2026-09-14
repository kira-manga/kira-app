package me.manga.kira.platform.cbz

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosCbzCompletenessTest {
    @Test
    fun missingUnreadableAndCorruptSourcesAbortAfterAnEarlierPageWasWritten() =
        iosCbzTest { fixture ->
            IosSourceFault.entries.forEach { fault ->
                val chapter = fault.ordinal + 1L
                val paths = fixture.pages(chapterId = chapter)
                val bad = paths.last()
                when (fault) {
                    IosSourceFault.MISSING -> fixture.system.delete(bad)
                    IosSourceFault.CORRUPT -> fixture.system.write(bad) { writeUtf8("not an image") }
                    IosSourceFault.UNREADABLE -> Unit
                }
                val previous = fixture.previousArchive(chapter)
                val originals = fixture.capture(paths)
                val system =
                    object : ForwardingFileSystem(fixture.system) {
                        override fun source(file: Path): Source {
                            if (fault == IosSourceFault.UNREADABLE && file == bad) throw IOException("read denied")
                            return super.source(file)
                        }
                    }
                var emitted = 0
                val encoder = observingEncoder(afterEmit = { emitted++ })
                val writer = IosCbzWriter(fixture.fileSystem(system), encoder)

                assertFailsWith<IOException> { writer.createCbz(paths, fixture.mangaId, chapter) }

                assertEquals(1, emitted)
                fixture.assertRetained(originals, previous, chapter)
            }
        }

    @Test
    fun anEmptyCodecResultOrFailureAfterOneBandCannotPublish() =
        iosCbzTest { fixture ->
            listOf(false, true).forEachIndexed { index, lateFailure ->
                val chapter = index + 1L
                val paths = fixture.pages(count = 1, chapterId = chapter)
                val previous = fixture.previousArchive(chapter)
                val originals = fixture.capture(paths)
                var emitted = 0
                val encoder =
                    if (lateFailure) {
                        observingEncoder(
                            beforeEmit = { if (emitted == 1) throw IOException("second band failed") },
                            afterEmit = { emitted++ },
                        )
                    } else {
                        IosCbzPageEncoder { _, _, _ -> }
                    }
                val writer = IosCbzWriter(fixture.fileSystem(), encoder)

                assertFailsWith<IOException> {
                    writer.createCbzWithSplitting(paths, fixture.mangaId, chapter, maxHeight = 24)
                }

                assertEquals(if (lateFailure) 1 else 0, emitted)
                fixture.assertRetained(originals, previous, chapter)
            }
        }

    @Test
    fun liveContainerRederivationKeepsEveryPageAndAllowsRealSplitting() =
        iosCbzTest { fixture ->
            val live = fixture.pages(count = 1)
            val stale = live.map { "/old-container/Documents/${it.name}".toPath() }
            var publications = 0
            val system =
                object : ForwardingFileSystem(fixture.system) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ) {
                        assertTrue(live.all { fixture.system.exists(it) })
                        publications++
                        super.atomicMove(source, target)
                    }
                }
            val writer = IosCbzWriter(fixture.fileSystem(system))

            val result = writer.createCbzWithSplitting(stale, fixture.mangaId, 1L, maxHeight = 24)

            assertEquals(fixture.destination(), result)
            assertEquals(1, publications)
            fixture.assertWebpDimensions(listOf(23 to 24, 23 to 24, 23 to 17))
            assertTrue(live.none { fixture.system.exists(it) })
            fixture.assertNoTemporary()
        }

    @Test
    fun failedAtomicReplacementNeverDeletesTheOldArchiveOrRetriesDestructively() =
        iosCbzTest { fixture ->
            val paths = fixture.pages()
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            var attempts = 0
            val system =
                object : ForwardingFileSystem(fixture.system) {
                    override fun atomicMove(
                        source: Path,
                        target: Path,
                    ) {
                        attempts++
                        throw IOException("rename denied")
                    }
                }

            assertFailsWith<IOException> {
                IosCbzWriter(fixture.fileSystem(system)).createCbz(paths, fixture.mangaId, 1L)
            }

            assertEquals(1, attempts)
            fixture.assertRetained(originals, previous)
        }

    @Test
    fun sinkWriteOrCloseFailureCannotPublishOrDeleteOriginals() =
        iosCbzTest { fixture ->
            listOf(false, true).forEachIndexed { index, onClose ->
                val chapter = index + 1L
                val paths = fixture.pages(chapterId = chapter)
                val previous = fixture.previousArchive(chapter)
                val originals = fixture.capture(paths)
                val system = IosCbzWriteFaultFileSystem(fixture.system, onClose)

                assertFailsWith<IOException> {
                    IosCbzWriter(fixture.fileSystem(system)).createCbz(paths, fixture.mangaId, chapter)
                }

                fixture.assertRetained(originals, previous, chapter)
            }
        }

    @Test
    fun cancellationAfterNativeEncodeCannotReachPublication() =
        iosCbzTest { fixture ->
            val paths = fixture.pages(count = 1)
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val encoder =
                observingEncoder(
                    beforeEmit = {
                        entered.complete(Unit)
                        withContext(NonCancellable) { withTimeout(15_000) { released.await() } }
                    },
                )
            val conversion = async { IosCbzWriter(fixture.fileSystem(), encoder).createCbz(paths, fixture.mangaId, 1L) }
            try {
                withTimeout(15_000) { entered.await() }
                conversion.cancel(CancellationException("conversion cancelled"))
                released.complete(Unit)
                assertFailsWith<CancellationException> { conversion.await() }
            } finally {
                released.complete(Unit)
                conversion.cancelAndJoin()
            }
            fixture.assertRetained(originals, previous)
        }

    @Test
    fun aReadableZipDirectoryWithDamagedPayloadIsNotPublished() =
        iosCbzTest { fixture ->
            val paths = fixture.pages()
            val previous = fixture.previousArchive()
            val originals = fixture.capture(paths)
            val system = IosCbzCorruptingZipFileSystem(fixture)

            assertFailsWith<IOException> {
                IosCbzWriter(fixture.fileSystem(system)).createCbz(paths, fixture.mangaId, 1L)
            }

            fixture.assertRetained(originals, previous)
        }

    @Test
    fun emptyRequestedRosterDoesNotReplaceAnExistingArchive() =
        iosCbzTest { fixture ->
            val previous = fixture.previousArchive()

            assertFailsWith<IllegalArgumentException> {
                IosCbzWriter(fixture.fileSystem()).createCbz(emptyList(), fixture.mangaId, 1L)
            }

            fixture.assertRetained(emptyMap(), previous)
            assertFalse(fixture.archiveEntries().isEmpty())
        }

    private fun observingEncoder(
        beforeEmit: suspend () -> Unit = {},
        afterEmit: () -> Unit = {},
    ): IosCbzPageEncoder =
        IosCbzPageEncoder { page, options, emit ->
            DefaultIosCbzPageEncoder.encode(page, options) { extension, bytes ->
                beforeEmit()
                emit(extension, bytes)
                afterEmit()
            }
        }
}

private enum class IosSourceFault { MISSING, UNREADABLE, CORRUPT }
