package me.manga.kira.platform.cbz

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CbzRetainedInputContractTest {
    @Test
    fun unsupportedRetentionNeverDelegatesToDestructiveEntryPoints() =
        runTest {
            val writer = LegacyOnlyWriter()

            assertFailsWith<UnsupportedOperationException> {
                writer.createCbzWithSplittingRetainingSources(listOf("input.png".toPath()), 1L, 1L)
            }

            assertEquals(0, writer.destructiveCalls)
        }

    private class LegacyOnlyWriter : CbzWriter {
        var destructiveCalls = 0
            private set

        override suspend fun createCbz(
            imagePaths: List<Path>,
            mangaId: Long,
            chapterId: Long,
            quality: Int,
        ): Path = destructiveWrite(imagePaths)

        override suspend fun createCbzWithSplitting(
            imagePaths: List<Path>,
            mangaId: Long,
            chapterId: Long,
            quality: Int,
            maxHeight: Int,
            maxMemoryBytes: Long,
        ): Path = destructiveWrite(imagePaths)

        private fun destructiveWrite(imagePaths: List<Path>): Path {
            destructiveCalls++
            return imagePaths.first()
        }
    }
}
