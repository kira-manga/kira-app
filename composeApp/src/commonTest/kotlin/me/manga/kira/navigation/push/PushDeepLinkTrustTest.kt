package me.manga.kira.navigation.push

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
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

    private object EmptySourcesDao : SourcesDao {
        override fun getAllSources(): Flow<List<SourcesEntity>> = flowOf(emptyList())

        override suspend fun insert(source: SourcesEntity): Long = 1L

        override suspend fun setEnabledByName(
            name: String,
            enabled: Boolean,
        ): Int = 0

        override suspend fun getBaseUrlFor(name: String): String? = null

        override fun getSiteStateByName(name: String): Flow<SourceState?> = flowOf(null)

        override suspend fun getSiteStateByNameSync(name: String): SourceState? = null

        override suspend fun updateBaseUrlAndVersionByName(
            name: String,
            baseUrl: String,
            version: Int,
        ): Int = 0

        override suspend fun updateImageBaseUrlAndVersionByName(
            apiName: String,
            newImageBaseUrl: String,
            newImageVersion: Int,
        ): Int = 0

        override suspend fun updateSiteStateByName(
            name: String,
            siteState: SourceState,
        ): Int = 0

        override suspend fun deleteSourceByName(name: String): Int = 0
    }

    /** No legacy repos → the config authority is the only one that can grant trust. */
    private val legacySources =
        SourcesRepository(
            sourcesDao = EmptySourcesDao,
            repos = emptySet(),
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = CoroutineScope(Dispatchers.Unconfined),
        )

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
            assertTrue(reader().isHostTrustedFor(legacySources, configTrust))
        }

    @Test
    fun reader_attackerCoverUrl_isRejected() =
        runTest {
            // manga/chapter urls are legitimate — only the cover points at a foreign host. Pre-fix
            // this passed the gate and the attacker cover was persisted into history/library.
            assertFalse(
                reader(coverUrl = "https://evil.example/steal.jpg").isHostTrustedFor(legacySources, configTrust),
            )
        }

    @Test
    fun reader_attackerMangaOrChapterUrl_isRejected() =
        runTest {
            assertFalse(
                reader(mangaUrl = "https://evil.example/series/1").isHostTrustedFor(legacySources, configTrust),
            )
            assertFalse(
                reader(chapterUrl = "https://evil.example/ch/2").isHostTrustedFor(legacySources, configTrust),
            )
        }

    @Test
    fun mangaDetail_ownHostTrusted_foreignHostRejected() =
        runTest {
            val own = PushDestination.MangaDetail(api = "Azora", url = "https://azoramoon.com/series/1")
            val foreign = PushDestination.MangaDetail(api = "Azora", url = "https://evil.example/series/1")
            assertTrue(own.isHostTrustedFor(legacySources, configTrust))
            assertFalse(foreign.isHostTrustedFor(legacySources, configTrust))
        }

    @Test
    fun tabDestinations_carryNoUrl_alwaysTrusted() =
        runTest {
            assertTrue(PushDestination.Updates.isHostTrustedFor(legacySources, configTrust))
            assertTrue(PushDestination.Home.isHostTrustedFor(legacySources, configTrust))
        }
}
