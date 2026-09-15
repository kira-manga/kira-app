package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.repository.MangaKey
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMetadataConcurrencyTest {
    @Test
    fun simultaneousAffinityActionsPreserveBothColumnsAndDoubleToggleRestoresValue() = runTest {
        LibraryMetadataFixture().use { fixture ->
            val before = fixture.seed()
            val key = MangaKey(before.manga.api, before.manga.language, before.manga.title)
            val boundary = MetadataOverlapDao(fixture.db.mangaDao())
            val repository = fixture.shared(boundary)
            listOf(async { repository.toggleLiked(key) }, async { repository.toggleWatchingNow(key) })
                .awaitAll().forEach { assertTrue(it.isSuccess) }
            val expected = before.copy(manga = before.manga.copy(isLiked = true, isWatchingNow = true))
            assertEquals(expected, fixture.snapshot(before.manga.id))
            assertEquals(0, boundary.snapshotReads.get(), "affinity writes must not read/replace a whole row")

            val repeated = fixture.shared(MetadataOverlapDao(fixture.db.mangaDao()))
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
            val repository = fixture.shared(boundary)
            listOf(
                async { repository.toggleLiked(MangaKey(manga.api, manga.language, manga.title)) },
                async { repository.updateCoverIfChanged(manga.api, manga.language, manga.title, NEW_COVER) },
            ).awaitAll().forEach { assertTrue(it.isSuccess) }
            val expected = before.withCover(NEW_COVER).let { it.copy(manga = it.manga.copy(isLiked = true)) }
            assertEquals(expected, fixture.snapshot(manga.id))
            assertEquals(0, boundary.snapshotReads.get(), "only the real cover transaction may read its parent")
            fixture.reopen()
            assertEquals(expected, fixture.snapshot(manga.id))
        }
    }
}

/**
 * Both repository calls resolve before either mutates. If a legacy read/replace path is restored,
 * both real Room snapshots are captured before either read returns, deterministically exposing
 * the lost update. Field-only methods bypass that obsolete snapshot barrier and use real SQL.
 */
private class MetadataOverlapDao(private val real: MangaDao) : MangaDao by real {
    private val resolutions = AtomicInteger()
    private val resolved = CompletableDeferred<Unit>()
    val snapshotReads = AtomicInteger()
    private val snapshotsReady = CompletableDeferred<Unit>()

    override suspend fun getIdByApiAndTitle(api: String, title: String): Long? {
        val id = real.getIdByApiAndTitle(api, title)
        if (resolutions.incrementAndGet() == 2) resolved.complete(Unit)
        resolved.await()
        return id
    }

    override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? {
        val snapshot = real.getMangaById(mangaId)
        if (snapshotReads.incrementAndGet() == 2) snapshotsReady.complete(Unit)
        snapshotsReady.await()
        return snapshot
    }
}
