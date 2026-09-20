package me.manga.kira.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.util.notification.CHAPTER_NOTIFICATION_CHANNEL
import me.manga.kira.core.util.notification.NotificationNativeCoverWitness
import me.manga.kira.core.util.notification.NotificationPostingShadow
import me.manga.kira.core.util.notification.NotificationRoomFixture
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.locale.LocaleOnlyTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

/** Actual Worker/Helper -> domain refresh -> one real Room writer; no successful repository double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LocaleOnlyTestApplication::class, shadows = [NotificationPostingShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryRefreshDiscoveryOwnershipTest {
    private lateinit var context: Application
    private lateinit var room: NotificationRoomFixture
    private lateinit var posting: NotificationPostingShadow
    private lateinit var covers: NotificationNativeCoverWitness
    private lateinit var prefs: SharedPrefsHelper

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        room = NotificationRoomFixture(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        posting = Shadow.extract(manager)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        covers = NotificationNativeCoverWitness()
        prefs = SharedPrefsHelper(MapSettings()).also { it.putString(LAST_UPDATED, PREVIOUS_SUCCESS) }
    }

    @After
    fun close() = room.close() // Every test awaits the structured refreshWork.run before returning.

    @Test
    fun removedAndReaddedDuringDetailsCannotReceiveTheOldOwnersDiscoveries() = runBlocking {
        assertReplacementRefused(chapterCount = 2)
    }

    @Test
    fun emptyDetailsStillRevalidatesTheOwnerRemovedAndReaddedDuringFetch() = runBlocking {
        assertReplacementRefused(chapterCount = 0)
    }

    @Test
    fun actualFetchedApiIsNotReplacedWithTheSavedApiAfterOptionalCoverRefusal() = runBlocking {
        assertForeignDetailsRefused { it.copy(api = "foreign-fixture") }
    }

    @Test
    fun actualFetchedUrlIsNotReplacedWithTheSavedUrlAfterOptionalCoverRefusal() = runBlocking {
        assertForeignDetailsRefused { it.copy(url = "https://manga.example/foreign") }
    }

    @Test
    fun acceptedAliasKeepsOriginalIdAndReturnsCurrentMetadataAndOnlyCommittedDiscoveries() = runBlocking {
        val original = room.manga()
        val chapters = room.chapters(original, 2)
        room.db.chapterDao().insertChapters(chapters.take(1))
        val moved = original.copy(
            url = "https://alias.example/manga", imageUrl = "https://cover.example/current.png",
            title = "Current local title", isLiked = true, isWatchingNow = true, lastOpenTimestamp = 91,
        )
        val witness = RefreshReceiptWitness(room.ownedLibrary)
        val source = RefreshSource(
            chapters.asReversed(),
            beforeDetails = { assertEquals(1, room.db.mangaDao().updateManga(moved)) },
            returnedDetails = { it.aliasDetails(moved.url) },
        )

        assertEquals(Result.success(), worker(source, witness).refreshWork.run())

        assertCapturedFetch(witness.requests.single(), original, WorkLocator(original.api, moved.url))
        assertAliasCommit(original, moved, witness.receipt())
        assertTrue(prefs.getString(LAST_UPDATED) != PREVIOUS_SUCCESS)
        covers.assertSingleDecode()
    }

    private suspend fun assertReplacementRefused(chapterCount: Int) {
        val original = room.manga()
        var replacement: SavedMangaEntity? = null
        val witness = RefreshReceiptWitness(room.ownedLibrary)
        val source = RefreshSource(room.chapters(original, chapterCount).asReversed(), beforeDetails = {
            room.db.libraryDeo().removeMangaWithChapters(original.id)
            replacement = room.manga(title = "Replacement must not be refreshed")
        })

        assertEquals(Result.failure(), worker(source, witness).refreshWork.run())

        val current = checkNotNull(replacement)
        assertTrue(current.id != original.id)
        assertEquals(current, room.db.mangaDao().getMangaById(current.id))
        assertCapturedFetch(witness.requests.single(), original, WorkLocator(original.api, original.url))
        assertEquals(chapterCount, witness.requests.single().fetched.details.chapters.size)
        assertRefusalWithoutWrites(witness, current.id)
    }

    private suspend fun assertForeignDetailsRefused(change: (MangaDetails) -> MangaDetails) {
        val original = room.manga()
        val witness = RefreshReceiptWitness(room.ownedLibrary)
        var actual: WorkLocator? = null
        val source = RefreshSource(room.chapters(original, 2).asReversed(), returnedDetails = {
            change(it).copy(title = "Foreign metadata", coverUrl = "https://cover.example/foreign.png").also { fetched ->
                actual = WorkLocator(fetched.api, fetched.url)
            }
        })

        assertEquals(Result.failure(), worker(source, witness).refreshWork.run())

        assertEquals(original, room.db.mangaDao().getMangaById(original.id))
        assertCapturedFetch(witness.requests.single(), original, checkNotNull(actual))
        assertRefusalWithoutWrites(witness, original.id)
    }

    private suspend fun assertRefusalWithoutWrites(witness: RefreshReceiptWitness, parentId: Long) {
        assertTrue(witness.result is AppResult.Failure)
        assertEquals(PREVIOUS_SUCCESS, prefs.getString(LAST_UPDATED))
        assertTrue(room.db.chapterDao().getChaptersByMangaIdR(parentId).isEmpty())
        assertTrue(room.updates().isEmpty())
        assertEquals(0, room.sql.chapterInserts.get())
        assertEquals(0, room.sql.notificationInserts.get())
        assertTrue(chapterPosts().isEmpty())
        covers.assertIdle()
    }

    private fun assertCapturedFetch(request: LibraryRefreshRequest, original: SavedMangaEntity, actual: WorkLocator) {
        val originalOwner = SavedWorkIdentity(original.id, WorkLocator(original.api, original.url))
        assertEquals(originalOwner, request.owner)
        assertEquals(originalOwner.locator, request.fetched.requested)
        assertEquals(actual, WorkLocator(request.fetched.details.api, request.fetched.details.url))
        assertEquals("", request.fetched.details.coverUrl) // Cover remains a separate best-effort write.
    }

    private suspend fun assertAliasCommit(
        original: SavedMangaEntity,
        moved: SavedMangaEntity,
        receipt: LibraryRefreshReceipt,
    ) {
        assertEquals(SavedWorkIdentity(original.id, WorkLocator(original.api, moved.url)), receipt.owner)
        assertEquals(1, receipt.addedChapters)
        assertEquals(listOf("2"), receipt.notifications.map { it.chapter.number })
        assertEquals(
            moved.copy(
                language = "fr", title = "Fetched alias title", description = "Fetched description",
                author = "Fetched author", status = "Completed", rating = "9", genres = listOf("Drama"),
            ),
            room.db.mangaDao().getMangaById(original.id),
        )
        room.assertStoredWithRealChapterIds(receipt)
        assertEquals(moved.url, receipt.notifications.single().manga.url)
        assertEquals(moved.imageUrl, receipt.notifications.single().manga.coverUrl)
        assertEquals("Fetched alias title", receipt.notifications.single().manga.title)
        assertEquals("fr", receipt.notifications.single().manga.language)
        assertEquals(receipt.notifications.map { it.notificationId.toInt() }, chapterPosts().map { it.first })
        assertEquals("Fetched alias title", chapterPosts().single().second.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(2, room.db.chapterDao().getChaptersByMangaIdR(original.id).size)
    }

    private fun worker(source: RefreshSource, witness: RefreshReceiptWitness): LibraryRefreshWorker =
        TestListenableWorkerBuilder<LibraryRefreshWorker>(context)
            .setWorkerFactory(
                refreshWorkerFactory(room.repository(), room.helper(covers.loader, witness), fixtureRegistry(source), prefs),
            )
            .setForegroundUpdater(ImmediateRefreshForeground)
            .build()

    private fun chapterPosts() = posting.posted.filter { it.second.channelId == CHAPTER_NOTIFICATION_CHANNEL }

    private fun MangaDetails.aliasDetails(alias: String) = copy(
        url = alias, coverUrl = "", language = "fr", title = "Fetched alias title",
        description = "Fetched description", author = "Fetched author", status = "Completed",
        rating = "9", genres = listOf("Drama"),
    )

    private companion object {
        const val LAST_UPDATED = "library_last_updated"
        const val PREVIOUS_SUCCESS = "2026-09-01T00:00:00"
    }
}

/** Observation only: every result, including refusal and zero counts, comes from the real writer. */
private class RefreshReceiptWitness(private val delegate: LibraryRepository) : LibraryRepository by delegate {
    val requests = mutableListOf<LibraryRefreshRequest>()
    var result: AppResult<List<LibraryRefreshReceipt>>? = null
        private set

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> {
        check(notify)
        this.requests.addAll(requests)
        return delegate.refresh(requests, notify).also { result = it }
    }

    fun receipt(): LibraryRefreshReceipt = when (val actual = result) {
        is AppResult.Success -> actual.value.single()
        else -> throw AssertionError("Expected a real committed refresh receipt, got $actual")
    }
}
