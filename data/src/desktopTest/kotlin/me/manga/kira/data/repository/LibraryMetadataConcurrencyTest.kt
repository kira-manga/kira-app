package me.manga.kira.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.mapper.savedIdentity
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMetadataConcurrencyTest {
    @Test
    fun simultaneousAffinityActionsPreserveBothColumnsAndDoubleToggleRestoresValue() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val key = before.manga.savedIdentity()
            val boundary = MetadataOverlapDao(fixture.db.mangaDao())
            val runtime = fixture.runtime(boundary, LibraryConcurrentWriteBarrier(RoomMangaWriteTransaction(fixture.db)))
            val repository = runtime.repository
            listOf(async { repository.toggleLiked(key) }, async { repository.toggleWatchingNow(key) })
                .awaitAll().forEach { assertTrue(it.isSuccess) }
            val expected = before.copy(manga = before.manga.copy(isLiked = true, isWatchingNow = true))
            assertEquals(expected, fixture.snapshot(before.manga.id))
            assertEquals(0, boundary.snapshotReads.get(), "affinity writes must not read/replace a whole row")

            val repeated = fixture.runtime(
                MetadataOverlapDao(fixture.db.mangaDao()), LibraryConcurrentWriteBarrier(RoomMangaWriteTransaction(fixture.db)),
            ).repository
            listOf(async { repeated.toggleLiked(key) }, async { repeated.toggleLiked(key) })
                .awaitAll().forEach { assertTrue(it.isSuccess) }
            assertEquals(expected, fixture.snapshot(before.manga.id))
        }
    }

    @Test
    fun affinityOverlappingCoverPreservesBothAndAllUnrelatedState() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val manga = before.manga
            val boundary = MetadataOverlapDao(fixture.db.mangaDao())
            val runtime = fixture.runtime(boundary, LibraryConcurrentWriteBarrier(RoomMangaWriteTransaction(fixture.db)))
            val repository = runtime.repository
            listOf(
                async { repository.toggleLiked(manga.savedIdentity()) },
                async { runtime.covers.updateCoverIfChanged(manga.savedIdentity(), manga.savedIdentity().locator, NEW_COVER) },
            ).awaitAll().forEach { assertTrue(it.isSuccess) }
            val expected = before.withCover(NEW_COVER).let { it.copy(manga = it.manga.copy(isLiked = true)) }
            assertEquals(expected, fixture.snapshot(manga.id))
            assertEquals(0, boundary.snapshotReads.get(), "only the real cover transaction may read its parent")
            fixture.reopen()
            assertEquals(expected, fixture.snapshot(manga.id))
        }
    }
}

/** Narrow writes use real Room. Any restored whole-row replacement is a deterministic failure. */
private class MetadataOverlapDao(private val real: MangaDao) : MangaDao by real {
    val snapshotReads = AtomicInteger()

    override suspend fun getIdByApiAndTitle(api: String, title: String): Long? =
        error("Metadata actions may not resolve a parent by title")

    override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? {
        snapshotReads.incrementAndGet()
        return real.getMangaById(mangaId)
    }

    override suspend fun updateManga(manga: SavedMangaEntity): Int =
        error("Metadata actions may not replace an entire parent row")

    override suspend fun update(manga: SavedMangaEntity) =
        error("Metadata actions may not replace an entire parent row")
}
