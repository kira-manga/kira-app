package me.manga.kira.navigation.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.runtime.ConfigHostTrust
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #8 intent-redirection gate ([isHostTrustedFor]): a push deep-link is navigated only when EVERY
 * url it carries belongs to its own source's declared hosts. 2026-07 audit: `Reader.coverUrl` was
 * previously unvalidated — a crafted-extras launch could persist an attacker cover URL that the
 * source-scoped image path later fetched with the source's stored Cookie/cf_clearance headers.
 */
class PushDeepLinkTrustTest {
    private class FakeUpdateManager(
        private val document: SourceConfigDocument,
    ) : SourceUpdateManager {
        private val _state =
            MutableStateFlow<UpdateState>(UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED))
        override val state: StateFlow<UpdateState> = _state.asStateFlow()

        override fun activeDocument(): SourceConfigDocument = document

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
    }

    private val configTrust =
        ConfigHostTrust(
            FakeUpdateManager(
                SourceConfigDocument(
                    schemaVersion = 1,
                    sources =
                        listOf(
                            SourceConfig(
                                api = "Azora",
                                language = "(AR)",
                                baseUrl = "https://azoramoon.com",
                                imageBase = "https://img.azora.net",
                                engine = "generic",
                            ),
                        ),
                ),
            ),
        )

    private fun reader(
        mangaUrl: String = "https://azoramoon.com/series/1",
        chapterUrl: String = "https://azoramoon.com/series/1/ch/2",
        coverUrl: String = "https://img.azora.net/covers/1.jpg",
    ) = PushDestination.Reader(
        api = "Azora",
        language = "(AR)",
        mangaUrl = mangaUrl,
        chapterUrl = chapterUrl,
        chapterNumber = "2",
        title = "t",
        coverUrl = coverUrl,
        chapterName = "",
    )

    @Test
    fun reader_allUrlsOnDeclaredHosts_isTrusted() =
        runTest {
            assertTrue(reader().isHostTrustedFor(configTrust))
        }

    @Test
    fun reader_attackerCoverUrl_isRejected() =
        runTest {
            // manga/chapter urls are legitimate — only the cover points at a foreign host. Pre-fix
            // this passed the gate and the attacker cover was persisted into history/library.
            assertFalse(
                reader(coverUrl = "https://evil.example/steal.jpg").isHostTrustedFor(configTrust),
            )
        }

    @Test
    fun reader_attackerMangaOrChapterUrl_isRejected() =
        runTest {
            assertFalse(
                reader(mangaUrl = "https://evil.example/series/1").isHostTrustedFor(configTrust),
            )
            assertFalse(
                reader(chapterUrl = "https://evil.example/ch/2").isHostTrustedFor(configTrust),
            )
        }

    @Test
    fun mangaDetail_ownHostTrusted_foreignHostRejected() =
        runTest {
            val own = PushDestination.MangaDetail(api = "Azora", url = "https://azoramoon.com/series/1")
            val foreign = PushDestination.MangaDetail(api = "Azora", url = "https://evil.example/series/1")
            assertTrue(own.isHostTrustedFor(configTrust))
            assertFalse(foreign.isHostTrustedFor(configTrust))
        }

    @Test
    fun tabDestinations_carryNoUrl_alwaysTrusted() =
        runTest {
            assertTrue(PushDestination.Updates.isHostTrustedFor(configTrust))
            assertTrue(PushDestination.Home.isHostTrustedFor(configTrust))
        }
}
