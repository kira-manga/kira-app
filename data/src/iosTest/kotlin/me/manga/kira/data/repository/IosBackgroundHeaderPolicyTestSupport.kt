package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.storage.StorageKeys
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadHeaderPolicy
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifest
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.presentation.features.download.domain.clean.ManifestPage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val ONLY_REFERER = mapOf("Referer" to "https://example.test/")
internal val CURRENT_STATIC = ONLY_REFERER + mapOf("X-Signed" to "signed-now", "X-New" to "not-previously-frozen")
internal val FROZEN_HEADERS = ONLY_REFERER + mapOf(
    "Cookie" to "old-cookie", "User-Agent" to "old-agent", "X-Captured" to "old-capture", "X-Signed" to "captured-override",
)

internal data class RetainedHeaderCase(
    val chapter: IosCbzChapter,
    val prior: ChapterArtifactClaim,
    val failed: ChapterDownloadEntity,
    val saved: SavedChapterEntity,
    val manifest: DownloadManifest,
) {
    fun assertRequestIdentity(request: TransferRequest, token: String) {
        assertEquals(chapter.saved.mangaId, request.mangaId)
        assertEquals(chapter.saved.id, request.chapterId)
        assertEquals(manifest.pages.single { it.index == request.pageIndex }.url, request.url)
        assertEquals(token, request.attemptToken)
        assertNotEquals(prior.token, token, "Only explicit Retry reserves the replacement token")
    }
}

internal suspend fun IosCbzFinalizationFixture.retainedHeaderCase(headerFreePage: Boolean): RetainedHeaderCase {
    val chapter = downloadNamed(seed())
    val prior = prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2, pageCount = if (headerFreePage) 3 else 2)
    val retained = manifest(chapter).copy(pages = buildList {
        add(ManifestPage(0, "https://example.test/page-0.png", FROZEN_HEADERS, attempts = 2))
        add(ManifestPage(1, "https://example.test/page-1.png", FROZEN_HEADERS, attempts = 2))
        if (headerFreePage) add(ManifestPage(2, "https://cdn.example.test/page-2.png", emptyMap(), attempts = 2))
    })
    assertNotNull(artifacts.ownership.files(prior) {
        DownloadManifestStore(appFileSystem).write(retained)
        system.delete(appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "image_1.png")
    })
    assertTrue(artifacts.fail(prior, "ordinary failure"))
    assertTrue(artifacts.settle(prior))
    val result = RetainedHeaderCase(chapter, prior, download(chapter), saved(chapter), retained)
    reopen()
    assertEquals(retained, manifest(chapter), "Resume uses the genuine retained on-disk manifest")
    return result
}

internal suspend fun IosCbzFinalizationFixture.assertRetainedHeaderCase(
    retained: RetainedHeaderCase,
    token: String,
    pageFailures: Int,
): Long {
    val row = download(retained.chapter)
    assertEquals(retained.failed.mangaId, row.mangaId)
    assertEquals(retained.failed.chapterId, row.chapterId)
    assertEquals(retained.failed.api, row.api)
    assertEquals(retained.failed.url, row.url)
    assertNotEquals(retained.failed.id, row.id)
    assertEquals(DownloadingState.RUNNING, row.state)
    assertHeaderCustody(row, token)
    assertEquals(retained.saved, saved(retained.chapter), "No library/read-position rewrite")
    assertEquals(retained.manifest.copy(attemptToken = token, pages = retained.manifest.pages.map {
        it.copy(attempts = if (it.index == 1) pageFailures else 0)
    }), manifest(retained.chapter), "Policy changes must not rewrite frozen URLs/headers or invent a new retry budget")
    val verified = appFileSystem.chapterDir(row.mangaId, row.chapterId) / "image_0.png"
    assertContentEquals(retained.chapter.pages.values.first(), system.read(verified) { readByteArray() })
    return row.id
}

private suspend fun IosCbzFinalizationFixture.assertHeaderCustody(row: ChapterDownloadEntity, token: String) {
    val custody = assertNotNull(db.chapterArtifactDao().get(row.chapterId))
    assertEquals(row.id, custody.downloadId)
    assertEquals(row.mangaId, custody.mangaId)
    assertEquals(row.url, custody.chapterUrl)
    assertEquals(token, custody.token)
    assertEquals(ChapterArtifactOperation.DOWNLOAD, custody.operation)
    assertFalse(custody.retiring)
}

internal class RetainedHeaderProvider(initial: DownloadHeaderPolicy) : ChapterPageProvider {
    private val current = MutableStateFlow(initial)
    private val resolves = MutableStateFlow(0)
    var policy: DownloadHeaderPolicy
        get() = current.value
        set(value) { current.value = value }
    val resolutions: Int get() = resolves.value
    override fun headerPolicy(api: String): DownloadHeaderPolicy {
        assertEquals("test", api)
        return current.value
    }
    override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
        resolves.update { it + 1 }
        error("Retained headers must not trigger a replacement page resolve")
    }
}

internal class HeaderReadSettings(private val backing: ObservableSettings = MapSettings()) : ObservableSettings by backing {
    private val reads = MutableStateFlow(0)
    private val afterRead = MutableStateFlow<(() -> Unit)?>(null)
    val captureReads: Int get() = reads.value
    fun onNextCaptureRead(action: () -> Unit) { check(afterRead.compareAndSet(null, action)) }
    override fun getString(key: String, defaultValue: String): String = backing.getString(key, defaultValue).also {
        if (key.startsWith("headers.") || key == StorageKeys.HEADERS_MAP_JSON) {
            reads.update { count -> count + 1 }
            afterRead.getAndUpdate { null }?.invoke()
        }
    }
}

internal class HeaderAdmissionHold {
    val entered = CompletableDeferred<ChapterArtifactClaim>()
    val release = CompletableDeferred<Unit>()
}

/** Delay real ownership admission, never replace the Room authorization result with a fake grant. */
internal class HeaderAdmissionDao(private val real: ChapterArtifactDao) : ChapterArtifactDao by real {
    private val next = MutableStateFlow<HeaderAdmissionHold?>(null)
    fun arm(hold: HeaderAdmissionHold) { check(next.compareAndSet(null, hold)) }
    override suspend fun canPublish(claim: ChapterArtifactClaim): Boolean {
        next.getAndUpdate { null }?.let {
            it.entered.complete(claim)
            it.release.await()
        }
        return real.canPublish(claim)
    }
}

/** Only requests actually handed off are live; a delivered failure retires that exact native key. */
internal class HeaderPageTransport(val recorded: ArtifactTestTransport) : BackgroundTransport by recorded {
    private val active = MutableStateFlow<Set<Triple<Long, String, Int>>>(emptySet())
    override suspend fun enqueue(requests: List<TransferRequest>) {
        active.update { prior -> prior + requests.map { it.key() } }
        recorded.enqueue(requests)
    }
    override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> =
        active.value.filter { it.first == chapterId && it.second == attemptToken }.map { it.third }.toSet()
    fun finish(request: TransferRequest) {
        val key = request.key()
        assertTrue(key in active.getAndUpdate { it - key })
    }
    private fun TransferRequest.key() = Triple(chapterId, attemptToken, pageIndex)
}
