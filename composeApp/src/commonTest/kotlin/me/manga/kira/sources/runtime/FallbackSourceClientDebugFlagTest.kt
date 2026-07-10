package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.logging.Logger
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the temporary debug switch [SourceDebugFlags.DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING] as
 * wired through [FallbackSourceClient]:
 *  - flag OFF (production default) → a failing primary still falls back to legacy (no regression),
 *  - flag ON → the generic failure surfaces verbatim, the legacy fallback is NEVER consulted, and a
 *    categorised diagnosis is logged so the failure type (Cloudflare/HTTP/parse/empty) is visible.
 */
class FallbackSourceClientDebugFlagTest {

    private val manga = Manga("S", "(AR)", "t", "u", "", null, emptyList())
    private val chapter = Chapter("01", "n", "c-url", null, false, false)

    @Test
    fun globalFlagDefaultsToFallbackOn() {
        // Guards against accidentally shipping the debug switch enabled.
        assertFalse(SourceDebugFlags.DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING)
    }

    @Test
    fun flagOff_failingPrimary_fallsBackToLegacy() = runTest {
        val fallback = RecordingClient(succeeds = true)
        val client = FallbackSourceClient(
            primary = FailingClient(),
            fallback = fallback,
            disableFallback = { false },
            log = NoopLogger,
        )

        assertTrue(client.home(1) is AppResult.Success)
        assertTrue(client.details(manga) is AppResult.Success)
        assertTrue(fallback.consulted, "legacy fallback should be consulted when the flag is OFF")
    }

    @Test
    fun flagOn_failingPrimary_surfacesGenericFailure_andSkipsLegacy() = runTest {
        val fallback = RecordingClient(succeeds = true)
        val logger = CapturingLogger()
        val client = FallbackSourceClient(
            primary = FailingClient(),
            fallback = fallback,
            disableFallback = { true },
            log = logger,
        )

        val home = client.home(1)
        val details = client.details(manga)
        val pages = client.pages(manga, chapter).toList()

        // The generic (primary) failure is surfaced verbatim — no legacy recovery.
        assertTrue(home is AppResult.Failure && home.error is AppError.Network.Http)
        assertTrue(details is AppResult.Failure)
        assertTrue(pages.single() is AppResult.Failure)
        assertFalse(fallback.consulted, "legacy fallback must NOT be consulted when the flag is ON")

        // Diagnosis logged, and the 403 is attributed to Cloudflare/headers (the tester's category).
        assertTrue(logger.messages.any { it.contains("FAILED") && it.contains("403") })
        assertTrue(logger.messages.any { it.contains("Cloudflare", ignoreCase = true) })
    }

    @Test
    fun flagOn_emptySuccess_isFlaggedAsPossibleSelectorMismatch() = runTest {
        val logger = CapturingLogger()
        val client = FallbackSourceClient(
            primary = EmptyOkClient(),
            fallback = RecordingClient(succeeds = true),
            disableFallback = { true },
            log = logger,
        )

        assertTrue(client.home(1) is AppResult.Success)
        assertTrue(logger.messages.any { it.contains("0 items") && it.contains("selector", ignoreCase = true) })
    }

    // --- #6: pages() empty-Success falls through to legacy (flag OFF, production) ------------------

    private val onePage = listOf(Page("img-1", emptyMap()))

    @Test
    fun pages_emptyPrimary_fallsBackToLegacyNonEmpty() = runTest {
        val fallback = StubPagesClient(onePage)
        val client = FallbackSourceClient(
            primary = StubPagesClient(emptyList()),
            fallback = fallback,
            disableFallback = { false },
            log = NoopLogger,
        )
        val result = client.pages(manga, chapter).toList().single()
        assertTrue(result is AppResult.Success && result.value == onePage, "empty primary → legacy's non-empty pages")
        assertTrue(fallback.consulted, "legacy consulted when the generic returns 0 pages")
    }

    @Test
    fun pages_nonEmptyPrimary_shortCircuits() = runTest {
        val fallback = StubPagesClient(onePage)
        val client = FallbackSourceClient(
            primary = StubPagesClient(onePage),
            fallback = fallback,
            disableFallback = { false },
            log = NoopLogger,
        )
        val result = client.pages(manga, chapter).toList().single()
        assertTrue(result is AppResult.Success && result.value == onePage)
        assertFalse(fallback.consulted, "a non-empty generic result short-circuits — no legacy fetch")
    }

    @Test
    fun pages_bothEmpty_emitsSingleEmptySuccess() = runTest {
        val fallback = StubPagesClient(emptyList())
        val client = FallbackSourceClient(
            primary = StubPagesClient(emptyList()),
            fallback = fallback,
            disableFallback = { false },
            log = NoopLogger,
        )
        val results = client.pages(manga, chapter).toList()
        assertEquals(1, results.size, "legacy-also-empty emits exactly once (no loop)")
        assertTrue(results.single() is AppResult.Success)
        assertTrue(fallback.consulted)
    }

    @Test
    fun pages_flagOn_emptyPrimary_surfacedVerbatim_skipsLegacy() = runTest {
        val fallback = StubPagesClient(onePage)
        val client = FallbackSourceClient(
            primary = StubPagesClient(emptyList()),
            fallback = fallback,
            disableFallback = { true },
            log = NoopLogger,
        )
        val result = client.pages(manga, chapter).toList().single()
        assertTrue(result is AppResult.Success && result.value.isEmpty(), "flag ON surfaces empty Success verbatim")
        assertFalse(fallback.consulted, "flag ON never consults legacy")
    }

    // --- fakes -------------------------------------------------------------------------------------

    /** pages() returns Success([pages]); records whether it was consulted. Other verbs inert. */
    private class StubPagesClient(
        private val pages: List<Page>,
        override val api: String = "S",
    ) : MangaSourceClient {
        var consulted = false
            private set
        override suspend fun home(page: Int) = AppResult.Success(emptyList<HomeFeedItem>())
        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())
        override suspend fun search(query: String, page: Int, filters: FilterSelections) = AppResult.Success(emptyList<HomeFeedItem>())
        override suspend fun details(manga: Manga) =
            AppResult.Success(MangaDetails(api, "(AR)", "t", "u", "", "", "", "", "", emptyList(), emptyList()))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> {
            consulted = true
            return flowOf(AppResult.Success(pages))
        }
    }


    private class FailingClient(override val api: String = "S") : MangaSourceClient {
        override suspend fun home(page: Int) = AppResult.Failure(AppError.Network.Http(403))
        override suspend fun featured(page: Int) = AppResult.Failure(AppError.Network.Http(403))
        override suspend fun search(query: String, page: Int, filters: FilterSelections) = AppResult.Failure(AppError.Network.Http(403))
        override suspend fun details(manga: Manga) = AppResult.Failure(AppError.Network.Http(403))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> =
            flowOf(AppResult.Failure(AppError.Network.Http(403)))
    }

    private class EmptyOkClient(override val api: String = "S") : MangaSourceClient {
        override suspend fun home(page: Int) = AppResult.Success(emptyList<HomeFeedItem>())
        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())
        override suspend fun search(query: String, page: Int, filters: FilterSelections) = AppResult.Success(emptyList<HomeFeedItem>())
        override suspend fun details(manga: Manga) =
            AppResult.Success(MangaDetails(api, "(AR)", "t", "u", "", "", "", "", "", emptyList(), emptyList()))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flowOf(AppResult.Success(emptyList()))
    }

    /** Records whether ANY verb was invoked, so a test can assert the fallback was/ wasn't consulted. */
    private class RecordingClient(private val succeeds: Boolean, override val api: String = "S") : MangaSourceClient {
        var consulted = false
            private set
        private fun <T> result(value: T): AppResult<T> {
            consulted = true
            return if (succeeds) AppResult.Success(value) else AppResult.Failure(AppError.Network.Http(500))
        }
        override suspend fun home(page: Int) = result(emptyList<HomeFeedItem>())
        override suspend fun featured(page: Int) = result(emptyList<FeaturedManga>())
        override suspend fun search(query: String, page: Int, filters: FilterSelections) = result(emptyList<HomeFeedItem>())
        override suspend fun details(manga: Manga) =
            result(MangaDetails(api, "(AR)", "fallback", "u", "", "", "", "", "", emptyList(), emptyList()))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> {
            consulted = true
            return flowOf(if (succeeds) AppResult.Success(emptyList()) else AppResult.Failure(AppError.Network.Http(500)))
        }
    }

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        override fun v(tag: String, message: String, throwable: Throwable?) { messages += message }
        override fun d(tag: String, message: String, throwable: Throwable?) { messages += message }
        override fun i(tag: String, message: String, throwable: Throwable?) { messages += message }
        override fun w(tag: String, message: String, throwable: Throwable?) { messages += message }
        override fun e(tag: String, message: String, throwable: Throwable?) { messages += message }
    }

    private object NoopLogger : Logger {
        override fun v(tag: String, message: String, throwable: Throwable?) {}
        override fun d(tag: String, message: String, throwable: Throwable?) {}
        override fun i(tag: String, message: String, throwable: Throwable?) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
