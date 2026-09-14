package me.manga.kira.presentation.features.download.domain.clean

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedPageTransferTest {
    private val fs = FileSystem.SYSTEM
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "page-transfer-${Random.nextLong().toULong()}"
    private val policy = PageBytePolicy(4)

    @AfterTest
    fun cleanup() = fs.deleteRecursively(directory, mustExist = false)

    @Test
    fun exactLimitWorksForDeclaredUnknownAndUnderstatedLengths() =
        runTest {
            fs.createDirectories(directory)
            for (declared in listOf(4L, null, -1L, 1L)) {
                val temporary = pageTemporaryPath(directory, 0)
                assertEquals(4L, transferPageBody(ByteReadChannel(byteArrayOf(1, 2, 3, 4)), declared, fs, temporary, policy))
                assertContentEquals(byteArrayOf(1, 2, 3, 4), fs.read(temporary) { readByteArray() })
                fs.delete(temporary)
            }
        }

    @Test
    fun excessFailsBeforeItCanReplaceAGoodPageAndCleansItsPartial() =
        runTest {
            fs.createDirectories(directory)
            val prior = directory / "image_0.png"
            fs.write(prior) { writeUtf8("good") }
            for (declared in listOf(5L, null, 1L)) {
                val temporary = pageTemporaryPath(directory, 0)
                val channel = ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5))
                assertFailsWith<PageByteLimitExceeded> { transferPageBody(channel, declared, fs, temporary, policy) }
                assertFalse(fs.exists(temporary))
                assertEquals("good", fs.read(prior) { readUtf8() })
                assertTrue(channel.isClosedForRead)
            }
        }

    @Test
    fun emptyAndStreamFailureCannotPublishAnImage() =
        runTest {
            fs.createDirectories(directory)
            val temporary = pageTemporaryPath(directory, 0)
            assertFailsWith<IOException> { transferPageBody(ByteReadChannel(byteArrayOf()), null, fs, temporary, policy) }
            assertFalse(fs.exists(temporary))
            val failed = ByteChannel(autoFlush = true).apply { cancel(IOException("stream failed")) }
            assertFailsWith<IOException> { transferPageBody(failed, null, fs, temporary, policy) }
            assertFalse(fs.exists(temporary))
        }

    @Test
    fun cancellationClosesChannelAndDeletesOnlyOwnedPartial() =
        runTest {
            fs.createDirectories(directory)
            val temporary = pageTemporaryPath(directory, 0)
            val channel = ByteChannel(autoFlush = true)
            val transfer = async { transferPageBody(channel, null, fs, temporary, policy) }
            channel.writeFully(byteArrayOf(1, 2))
            runCurrent()
            assertTrue(fs.exists(temporary))
            transfer.cancelAndJoin()
            assertFalse(fs.exists(temporary))
            assertTrue(channel.isClosedForRead)
        }

    @Test
    fun exclusiveCreateCollisionMustNotDeleteSomeoneElsesPartial() =
        runTest {
            fs.createDirectories(directory)
            val temporary = pageTemporaryPath(directory, 0)
            fs.write(temporary) { writeUtf8("owned elsewhere") }
            assertFailsWith<IOException> { transferPageBody(ByteReadChannel(byteArrayOf(1)), null, fs, temporary, policy) }
            assertEquals("owned elsewhere", fs.read(temporary) { readUtf8() })
        }

    @Test
    fun cleanupFailureDoesNotMaskTheOriginalLimitFailure() =
        runTest {
            fs.createDirectories(directory)
            val temporary = pageTemporaryPath(directory, 0)
            val failingDelete =
                object : ForwardingFileSystem(fs) {
                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ) = throw IOException("cleanup failed")
                }
            val failure =
                assertFailsWith<PageByteLimitExceeded> {
                    transferPageBody(ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5)), null, failingDelete, temporary, policy)
                }
            assertEquals("cleanup failed", failure.suppressedExceptions.single().message)
        }
}
