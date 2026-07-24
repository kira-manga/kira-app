package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterIdUrl
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Data-boundary routing verification: catalog sources use [SourceRegistry], absent sources fail
 * closed, downloaded chapters keep their local fast path, registry failures surface unchanged, and
 * cancellation propagates. There is no alternate or inferred source implementation.
 */
class AzoraDataRoutingTest {

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private fun azora(url: String = "https://api.azoramoon.com/api/post/?postId=1") =
        Manga(api = "Azora", language = "(AR)", title = "t", url = url, coverUrl = "", rating = null, genres = emptyList())

    private fun other() =
        Manga(api = "Other", language = "(EN)", title = "t", url = "u", coverUrl = "", rating = null, genres = emptyList())

    private fun chapter(url: String = "https://api.azoramoon.com/api/chapter?chapterId=1") =
        Chapter(number = "1", name = "c", url = url, date = null, isDownloaded = false, isBookmarked = false)

    // --- details routing -------------------------------------------------------------------------

    @Test
    fun details_azora_routes_through_registry_not_legacy() = runTest {
        val sentinel = MangaDetails("Azora", "(AR)", "GENERIC AZORA", "u", "", "", "", "", "", emptyList(), emptyList())
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, details = { AppResult.Success(sentinel) })
        }
        val repo = MangaDetailsRepositoryImpl(testDispatchers, registry)

        val result = repo.fetchDetails(azora())
        assertEquals(AppResult.Success(sentinel), result) // came from the registry, not legacy
        assertEquals(listOf("Azora"), registry.getCalls) // registry WAS consulted for Azora
    }

    @Test
    fun details_source_absent_from_catalog_fails_closed() = runTest {
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) { error("inactive source must not have a client") }
        val repo = MangaDetailsRepositoryImpl(testDispatchers, registry)

        val result = repo.fetchDetails(other())
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation.SourceUnavailable && error.api == "Other")
        assertEquals(listOf("Other"), registry.getCalls)
    }

    @Test
    fun details_azora_registry_failure_is_surfaced_through_data() = runTest {
        // Generic-only: :data surfaces the registry client's failure unchanged.
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, details = { AppResult.Failure(AppError.Network.Http(403)) })
        }
        val repo = MangaDetailsRepositoryImpl(testDispatchers, registry)

        val result = repo.fetchDetails(azora())
        assertTrue(result is AppResult.Failure && (result.error as? AppError.Network.Http)?.statusCode == 403)
    }

    @Test
    fun details_azora_cancellation_propagates() = runTest {
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, details = { throw CancellationException("cancelled") })
        }
        val repo = MangaDetailsRepositoryImpl(testDispatchers, registry)

        assertFailsWith<CancellationException> { repo.fetchDetails(azora()) }
    }

    // --- pages routing ---------------------------------------------------------------------------

    private fun pagesRepo(
        registry: SourceRegistry,
        dao: ChapterDao = FakeChapterDao(),
        cbz: CbzReader = FakeCbzReader(),
        appFs: AppFileSystem = TempDirAppFileSystem(),
    ) = ChapterPagesRepositoryImpl(testDispatchers, dao, cbz, registry, appFs)

    @Test
    fun pages_azora_routes_through_registry_not_legacy() = runTest {
        val sentinel = listOf(Page("https://img.azoramoon.com/1.webp", emptyMap()))
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, pages = { AppResult.Success(sentinel) })
        }
        val result = pagesRepo(registry).fetchPages(azora(), chapter()).first()
        assertEquals(AppResult.Success(sentinel), result)
        assertEquals(listOf("Azora"), registry.getCalls)
    }

    @Test
    fun pages_source_absent_from_catalog_fails_closed() = runTest {
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) { error("inactive source must not have a client") }
        val result = pagesRepo(registry).fetchPages(other(), chapter("u")).first()
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation.SourceUnavailable && error.api == "Other")
        assertEquals(listOf("Other"), registry.getCalls)
    }

    @Test
    fun pages_azora_downloaded_chapter_uses_local_files_not_registry() = runTest {
        // Offline preservation: a downloaded chapter serves local files even for the piloted Azora,
        // and the registry is NOT consulted (the offline fast-path wins first). The loose per-page
        // paths are re-derived under the live chapter dir (mangaId=10, chapterId=1) where the files
        // actually exist, so the served URLs point at the current location.
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf("/d/1.webp", "/d/2.webp"),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val appFs = TempDirAppFileSystem().apply { seedChapterFiles(10L, 1L, "1.webp", "2.webp") }
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) { error("registry must not be consulted for a downloaded chapter") }

        try {
            val result = pagesRepo(registry, dao, appFs = appFs).fetchPages(azora(), chapter("chap-url")).first()
            val pages = (result as AppResult.Success).value
            val dir = appFs.chapterDir(10L, 1L)
            assertEquals(
                listOf(toExpectedFileUrl("$dir/1.webp"), toExpectedFileUrl("$dir/2.webp")),
                pages.map { it.url },
            )
            assertEquals(emptyList(), registry.getCalls)
        } finally {
            appFs.cleanUp()
        }
    }

    @Test
    fun pages_loose_downloaded_chapter_missing_files_falls_back_to_network() = runTest {
        // r2-hot-2: loose downloaded pages whose files no longer exist anywhere (e.g. iOS container
        // change wiped them, isDownloaded still true) must NOT be served as broken file:// URLs —
        // the reader falls through to the source fetch.
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf("/gone/1.webp", "/gone/2.webp"),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val appFs = TempDirAppFileSystem() // nothing seeded → no file exists
        val sentinel = listOf(Page("https://img.azoramoon.com/1.webp", emptyMap()))
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, pages = { AppResult.Success(sentinel) })
        }

        try {
            val result = pagesRepo(registry, dao, appFs = appFs).fetchPages(azora(), chapter("chap-url")).first()
            assertEquals(AppResult.Success(sentinel), result, "no readable local pages -> source fallback")
            assertEquals(listOf("Azora"), registry.getCalls)
        } finally {
            appFs.cleanUp()
        }
    }

    @Test
    fun pages_azora_registry_failure_is_surfaced_through_data() = runTest {
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, pages = { AppResult.Failure(AppError.Network.Http(403)) })
        }
        val result = pagesRepo(registry).fetchPages(azora(), chapter()).first()
        assertTrue(result is AppResult.Failure && (result.error as? AppError.Network.Http)?.statusCode == 403)
    }

    // --- B2: loose pages gone, published CBZ present (finalize-swap window / manual compressor) --

    @Test
    fun pages_looseGone_existingCbz_isExtractedInsteadOfNetwork() = runTest {
        // B2 (reader): the background finalize deletes the loose source pages before Room is
        // repointed from the loose list to the [cbz] path — during that window (or after a kill in
        // it, or after a manual compressor run) Room still lists loose paths while only the .cbz is
        // on disk. The reader must extract the durable CBZ, NOT silently re-download from network.
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf("/gone/1.webp", "/gone/2.webp"),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val appFs = TempDirAppFileSystem() // nothing seeded → no loose file exists
        val canonical = "/current/manga/10/chapter_1/chapter_1.cbz".toPath()
        val cbz = FakeCbzReader(
            canonical = canonical,
            existsCanonical = true,
            extractFor = { p -> if (p == canonical) listOf("/x/0.webp".toPath(), "/x/1.webp".toPath()) else emptyList() },
        )
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) { error("registry must not be consulted; the existing CBZ serves the pages") }

        try {
            val result = pagesRepo(registry, dao, cbz, appFs).fetchPages(azora(), chapter("chap-url")).first()
            val pages = (result as AppResult.Success).value
            assertEquals(listOf("file:///x/0.webp", "file:///x/1.webp"), pages.map { it.url })
            assertEquals(listOf(canonical), cbz.extractCalls, "extracted the published CBZ at its canonical path")
            assertEquals(emptyList(), registry.getCalls)
        } finally {
            appFs.cleanUp()
        }
    }

    @Test
    fun pages_looseGone_cbzExtractsEmpty_fallsBackToNetwork() = runTest {
        // B2 guard-rail: a present-but-unreadable CBZ (corrupt/empty archive) must fall through to
        // the network fetch — never surface an empty Success (zero readable pages) to the reader.
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf("/gone/1.webp", "/gone/2.webp"),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val appFs = TempDirAppFileSystem()
        val cbz = FakeCbzReader(existsCanonical = true, extractFor = { emptyList() })
        val sentinel = listOf(Page("https://img.azoramoon.com/1.webp", emptyMap()))
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, pages = { AppResult.Success(sentinel) })
        }

        try {
            val result = pagesRepo(registry, dao, cbz, appFs).fetchPages(azora(), chapter("chap-url")).first()
            assertEquals(AppResult.Success(sentinel), result, "empty CBZ extraction -> source fallback")
            assertEquals(listOf("Azora"), registry.getCalls)
        } finally {
            appFs.cleanUp()
        }
    }

    // --- downloaded-CBZ path resolution (container-UUID staleness fix) ---------------------------

    @Test
    fun pages_downloadedCbz_staleStoredPath_recoversViaRederivedCurrentPath() = runTest {
        // The chapter is downloaded; the STORED localImagePath is a stale absolute CBZ path captured
        // at download time (an old iOS container UUID). The CBZ now lives at the canonical current
        // filesDir path. The reader must extract from the RE-DERIVED path and serve local pages —
        // NOT log "CBZ file does not exist" and fall back to the network.
        val canonical = "/current/manga/10/chapter_1/chapter_1.cbz".toPath()
        val stale = "/var/mobile/Containers/Data/Application/OLD-UUID/Documents/manga/10/chapter_1/chapter_1.cbz"
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf(stale),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val cbz = FakeCbzReader(
            canonical = canonical,
            existsCanonical = true,
            extractFor = { p -> if (p == canonical) listOf("/x/0.webp".toPath(), "/x/1.webp".toPath()) else emptyList() },
        )
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) { error("registry must not be consulted; CBZ resolves locally") }

        val result = pagesRepo(registry, dao, cbz).fetchPages(azora(), chapter("chap-url")).first()

        val pages = (result as AppResult.Success).value
        assertEquals(listOf("file:///x/0.webp", "file:///x/1.webp"), pages.map { it.url })
        assertEquals(listOf(canonical), cbz.extractCalls, "extracted from the re-derived current path, not the stale stored path")
        assertEquals(emptyList(), registry.getCalls)
    }

    @Test
    fun pages_downloadedCbz_missingEverywhere_fallsBackToNetwork() = runTest {
        // Neither the re-derived current path nor the stored path has a readable CBZ -> the chapter
        // genuinely has no local file, so the reader falls back to the source (the stored path is
        // still tried for back-compat before giving up).
        val stale = "/OLD/manga/10/chapter_1/chapter_1.cbz"
        val downloaded = SavedChapterEntity(
            id = 1, mangaId = 10, name = "c", number = "1", url = "chap-url",
            isDownloaded = true, localImagePaths = listOf(stale),
        )
        val dao = FakeChapterDao(idByUrl = { if (it == "chap-url") 1L else null }, byId = { if (it == 1L) downloaded else null })
        val cbz = FakeCbzReader(existsCanonical = false, extractFor = { emptyList() })
        val sentinel = listOf(Page("https://img.azoramoon.com/1.webp", emptyMap()))
        val registry = FakeSourceRegistry(piloted = setOf("Azora")) {
            StubSourceClient(it, pages = { AppResult.Success(sentinel) })
        }

        val result = pagesRepo(registry, dao, cbz).fetchPages(azora(), chapter("chap-url")).first()

        assertEquals(AppResult.Success(sentinel), result, "no readable local CBZ -> source fallback")
        assertEquals(listOf(stale.toPath()), cbz.extractCalls, "stored path is tried (back-compat) when the current path is absent")
        assertEquals(listOf("Azora"), registry.getCalls)
    }

    // --- fakes -----------------------------------------------------------------------------------

    /**
     * Real okio [FileSystem.SYSTEM] over a unique temp root so the loose-page existence check
     * (re-derived chapter-dir vs stored path) exercises genuine `exists()`. Same pattern as
     * [LibraryRepositoryRemoveTest]'s TempDirFs.
     */
    private class TempDirAppFileSystem : AppFileSystem {
        private val fs: FileSystem = FileSystem.SYSTEM
        private val root: Path =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "yami-pages-test-${Random.nextLong().toString().trimStart('-')}"
        override val filesDir: Path = root
        override val cacheDir: Path = root / "cache"
        override fun fileSystem(): FileSystem = fs
        fun cleanUp() = fs.deleteRecursively(root, mustExist = false)
    }

    private fun TempDirAppFileSystem.seedChapterFiles(mangaId: Long, chapterId: Long, vararg names: String) {
        val dir = chapterDir(mangaId, chapterId)
        fileSystem().createDirectories(dir)
        names.forEach { name -> fileSystem().write(dir / name) { writeUtf8("img") } }
    }

    /** Mirror of the production [ChapterPagesRepositoryImpl.toFileUrl] encoder for a clean path. */
    private fun toExpectedFileUrl(path: String): String {
        val normalized = path.replace('\\', '/')
        val withLeadingSlash = if (normalized.startsWith("/")) normalized else "/$normalized"
        return "file://$withLeadingSlash"
    }

    private class FakeSourceRegistry(
        private val piloted: Set<String>,
        private val client: (String) -> MangaSourceClient?,
    ) : SourceRegistry {
        val getCalls = mutableListOf<String>()
        override fun get(api: String): MangaSourceClient? {
            getCalls += api
            return if (api in piloted) client(api) else null
        }
        override fun isConfigBacked(api: String): Boolean = api in piloted
        override fun descriptor(api: String): RuntimeSourceDescriptor? =
            if (api in piloted) fakeDescriptor(api) else null
        override fun genericDescriptors(): List<RuntimeSourceDescriptor> = piloted.map(::fakeDescriptor)
    }

    private class StubSourceClient(
        override val api: String,
        private val home: () -> AppResult<List<HomeFeedItem>> = { AppResult.Success(emptyList()) },
        private val search: () -> AppResult<List<HomeFeedItem>> = { AppResult.Success(emptyList()) },
        private val details: () -> AppResult<MangaDetails> = { fail("details not expected") },
        private val pages: () -> AppResult<List<Page>> = { fail("pages not expected") },
    ) : MangaSourceClient {
        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = home()
        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = AppResult.Success(emptyList())
        override suspend fun search(query: String, page: Int, filters: FilterSelections): AppResult<List<HomeFeedItem>> = search()
        override suspend fun details(manga: Manga): AppResult<MangaDetails> = details()
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flowOf(pages())
    }

    private class FakeChapterDao(
        private val idByUrl: (String) -> Long? = { null },
        private val byId: (Long) -> SavedChapterEntity? = { null },
    ) : ChapterDao {
        override suspend fun getChapterIdByUrl(url: String): Long? = idByUrl(url)
        override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? = byId(chapterId)
        override suspend fun getChapterIdsByUrlsBatch(urls: List<String>): List<Long> = urls.mapNotNull { idByUrl(it) }
        override suspend fun getChapterIdUrlPairsBatch(urls: List<String>) =
            urls.mapNotNull { url -> idByUrl(url)?.let { ChapterIdUrl(id = it, url = url) } }
        override suspend fun getChapterIdUrlPairsForMangaBatch(mangaId: Long, urls: List<String>) =
            urls.mapNotNull { url -> idByUrl(url)?.let { ChapterIdUrl(id = it, url = url) } }

        // --- unused surface ----------------------------------------------------------------------
        override suspend fun getAllDownloadedChapters(): List<SavedChapterEntity> = emptyList()
        override fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> = flowOf(emptyList())
        override suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long> = error("unused")
        override suspend fun insertAll(chapters: List<SavedChapterEntity>) = error("unused")
        override suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>) = error("unused")
        override suspend fun markChapterDownloaded(chapterId: Long) = error("unused")
        override suspend fun toggleChapterBookmark(chapterId: Long) = error("unused")
        override suspend fun markChapterAsRead(chapterId: Long, currentTime: Long) = error("unused")
        override suspend fun markChapterIsNew(chapterId: Long) = error("unused")
        override fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?> = flowOf(null)
        override fun getChapterByUrl(url: String): Flow<SavedChapterEntity?> = flowOf(null)
        override suspend fun markChaptersNotDownloaded(ids: List<Long>, emptyList: List<String>) = error("unused")
        override suspend fun deleteChapterById(chapterId: Long) = error("unused")
        override suspend fun markChaptersReadBatch(chapterIds: List<Long>) = error("unused")
        override suspend fun toggleChaptersReadBatch(chapterIds: List<Long>) = error("unused")
        override suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>) = error("unused")
        override suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity> = error("unused")
        override suspend fun updateChapter(chapter: SavedChapterEntity) = error("unused")
    }

    private class FakeCbzReader(
        private val canonical: Path = "/current/manga/10/chapter_1/chapter_1.cbz".toPath(),
        private val existsCanonical: Boolean = false,
        private val extractFor: (Path) -> List<Path> = { emptyList() },
    ) : CbzReader {
        /** Paths passed to [extractImages], in order — proves WHICH path the resolver opened. */
        val extractCalls = mutableListOf<Path>()
        override fun cbzPath(mangaId: Long, chapterId: Long): Path = canonical
        override fun cbzExists(mangaId: Long, chapterId: Long): Boolean = existsCanonical
        override suspend fun pageCount(cbzPath: Path): Int = 0
        override suspend fun extractImages(cbzPath: Path, mangaId: Long, chapterId: Long): List<Path> {
            extractCalls += cbzPath
            return extractFor(cbzPath)
        }
        override suspend fun deleteCbz(mangaId: Long, chapterId: Long): Boolean = false
        override suspend fun cleanupExtractedCache(mangaId: Long, chapterId: Long) = Unit
    }
}
