package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import me.manga.kira.sources.config.RemoteSourceConfigManager
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sources Migration — Phase 4. End-to-end DOWNLOAD-seam parity: exercises the FULL download path with
 * the REAL shipping config — `RegistryChapterPageProvider` → real `DefaultSourceRegistry` (built from
 * `CONFIG_BACKED_SOURCES_JSON`, generic-with-legacy-fallback) → real `GenericSourceClient` over fake HTTP
 * → `DownloadPage(url, headers)`. Complements Phase 3's `RegistryChapterPageProviderTest` (which used
 * stub clients to prove routing/mapping) by proving the real config + engine produce correct download
 * pages: page ORDER (CBZ ordering), URL absolutization, and per-page download HEADERS.
 *
 * Two representative sources: Azora (JSON, `page.order`, header-free) and Zazamanga (HTML, static Referer).
 */
class DownloadPageSeamParityTest {

    private val azoraChapterUrl = "https://api.azoramoon.com/api/chapter?chapterId=1"
    private val zazaChapterUrl = "https://www.zazamanga.com/manga/x/chapter-1"

    // Azora chapter-images payload with DELIBERATELY SCRAMBLED order (2,1,3) → the engine's page.order
    // sort must reorder to 1,2,3 (the CBZ page order a download writes).
    private val azoraPagesJson = """
        { "chapter": { "images": [
            { "order": 2, "url": "https://storage.azoramoon.com/op/2.jpg" },
            { "order": 1, "url": "https://storage.azoramoon.com/op/1.jpg" },
            { "order": 3, "url": "https://storage.azoramoon.com/op/3.jpg" }
        ] } }
    """.trimIndent()

    private val zazaPagesHtml = """
        <html><body>
        <div class="reading-content">
          <img class="wp-manga-chapter-img" src="https://cdn4.zinmanga1.com/x/0.webp"/>
          <img class="wp-manga-chapter-img" src="https://cdn4.zinmanga1.com/x/1.webp"/>
        </div>
        </body></html>
    """.trimIndent()

    private val fixtures = mapOf(
        azoraChapterUrl to azoraPagesJson,
        zazaChapterUrl to zazaPagesHtml,
    )

    /** The real registry assembly (mirrors `DefaultSourceRegistryTest.realRegistry`) but with a real
     *  `GenericSourceClient` factory over the canned page fixtures. */
    private fun provider(): RegistryChapterPageProvider {
        val registry = DefaultSourceRegistry(
            updateManager = RemoteSourceConfigManager(
                store = BundledSourceConfigStore(CONFIG_BACKED_SOURCES_JSON),
                verifier = DenyRemoteSignatureVerifier(),
                validator = DefaultSourceConfigValidator(DefaultStrategyRegistry()),
                remote = null,
            ),
            genericClientFactory = { cfg -> GenericSourceClient(cfg, MapFakeHttp(fixtures), NoopHeaderStore()) },
        )
        return RegistryChapterPageProvider(registry)
    }

    @Test
    fun azora_download_pages_sorted_by_order_absolute_and_header_free() = runTest {
        val pages: List<DownloadPage>? = provider().pagesOrNull(
            api = "Azora",
            mangaUrl = "https://api.azoramoon.com/api/post/?postId=1",
            mangaLanguage = "(AR)",
            chapterUrl = azoraChapterUrl,
        )
        assertNotNull(pages)
        // sorted 1,2,3 despite the scrambled (2,1,3) fixture; all absolute
        assertEquals(
            listOf(
                "https://storage.azoramoon.com/op/1.jpg",
                "https://storage.azoramoon.com/op/2.jpg",
                "https://storage.azoramoon.com/op/3.jpg",
            ),
            pages.map { it.url },
        )
        assertTrue(pages.all { it.headers.isEmpty() }) // header-free source → no download headers
    }

    @Test
    fun zazamanga_download_pages_carry_referer_header() = runTest {
        val pages = provider().pagesOrNull(
            api = "Zazamanga",
            mangaUrl = "https://www.zazamanga.com/manga/x",
            mangaLanguage = "(EN)",
            chapterUrl = zazaChapterUrl,
        )
        assertNotNull(pages)
        assertEquals(
            listOf("https://cdn4.zinmanga1.com/x/0.webp", "https://cdn4.zinmanga1.com/x/1.webp"),
            pages.map { it.url },
        )
        // the static Referer is load-bearing — the CDN 403s without it on the download GET
        assertTrue(pages.all { it.headers["Referer"] == "https://www.zazamanga.com/" })
    }

    @Test
    fun non_config_source_fails_closed_without_legacy_fallback() = runTest {
        assertFailsWith<GenericPagesFailedException> {
            provider().pagesOrNull("NotPiloted", "https://x.test/m", "(EN)", "https://x.test/m/c1")
        }
    }
}
