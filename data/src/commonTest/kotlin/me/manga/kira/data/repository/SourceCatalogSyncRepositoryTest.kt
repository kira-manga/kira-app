package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sources Migration — Phase 2. [SourceCatalogSyncRepositoryImpl] makes the active config document
 * the source of truth for the `sources` table:
 *  - seeds a missing config-backed (engine == "generic") source, disabled by default;
 *  - leaves legacy-engine config entries out of the catalog;
 *  - on a `config.baseUrl` / `config.imageBase` change, migrates stored URLs (via [SourceUrlMigrator])
 *    BEFORE bumping the stored base + version, and preserves the user's enable/disable choice;
 *  - costs zero writes in steady state (base unchanged).
 */
class SourceCatalogSyncRepositoryTest {
    // Lifecycle-metadata fields (retirement Phase 3) are applied via `.copy(...)` at the call
    // sites — keeps this builder at the shared shape (and under the LongParameterList threshold).
    private fun cfg(
        api: String,
        baseUrl: String,
        imageBase: String = "",
        engine: String = "generic",
        priority: Int = 0,
        language: String = "(AR)",
    ) = SourceConfig(api = api, language = language, baseUrl = baseUrl, imageBase = imageBase, engine = engine, priority = priority)

    private fun doc(vararg sources: SourceConfig) = SourceConfigDocument(schemaVersion = 1, sources = sources.toList())

    private fun repo(
        document: SourceConfigDocument,
        sources: StatefulSourcesDao,
        manga: StatefulMangaDao = StatefulMangaDao(),
        chapter: StatefulChapterDao = StatefulChapterDao(),
        history: StatefulHistoryDao = StatefulHistoryDao(),
        notification: StatefulNotificationDao = StatefulNotificationDao(),
    ) = SourceCatalogSyncRepositoryImpl(
        updateManager = FixedUpdateManager(document),
        sourcesDao = sources,
        migrator = SourceUrlMigrator(manga, chapter, history, notification),
        dispatchers = testDispatchers,
    )

    @Test
    fun seedsMissingGenericSource_disabledByDefault_andSkipsLegacyEngine() =
        runTest {
            val sources = StatefulSourcesDao() // empty table
            val document =
                doc(
                    cfg("Azora", "https://azora.test", imageBase = "https://img.azora.test", priority = 5),
                    cfg("LegacyOnly", "https://legacy.test", engine = "legacy"),
                )

            val result = repo(document, sources).syncFromConfig()

            assertTrue(result is AppResult.Success)
            assertEquals(1, sources.inserts.size)
            val seeded = sources.inserts.single()
            assertEquals("Azora", seeded.name)
            assertEquals(false, seeded.isEnabled) // disabled by default — onboarding enables by language
            assertEquals("https://azora.test", seeded.baseUrl)
            assertEquals("https://img.azora.test", seeded.imageBaseUrl)
            assertEquals(5, seeded.priority)
            // legacy-engine config entry is NOT seeded into the catalog
            assertNull(sources.current().firstOrNull { it.name == "LegacyOnly" })
        }

    @Test
    fun baseUrlChange_migratesPageUrls_bumpsVersion_preservesEnabled() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://old.azora.test", isEnabled = true, baseVersion = 2)),
                )
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://old.azora.test/manga/1")))
            val document = doc(cfg("Azora", "https://new.azora.test"))

            repo(document, sources, manga = manga).syncFromConfig()

            // stored manga URL migrated to the new host
            assertEquals("https://new.azora.test/manga/1", manga.rows.single().url)
            // base URL + version updated (version bumped from 2 → 3)
            assertEquals(Triple("Azora", "https://new.azora.test", 3), sources.baseUrlUpdates.single())
            // no new row inserted; the user's enabled choice is preserved
            assertEquals(0, sources.inserts.size)
            assertEquals(true, sources.current().single { it.name == "Azora" }.isEnabled)
        }

    @Test
    fun sameBaseUrl_writesNothing() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://azora.test", imageBaseUrl = "https://img.azora.test")),
                )
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://azora.test/manga/1")))
            val document = doc(cfg("Azora", "https://azora.test", imageBase = "https://img.azora.test"))

            repo(document, sources, manga = manga).syncFromConfig()

            assertEquals(0, sources.inserts.size)
            assertEquals(0, sources.baseUrlUpdates.size)
            assertEquals(0, sources.imageBaseUpdates.size)
            assertEquals(0, manga.updates.size)
        }

    @Test
    fun imageBaseChange_migratesImageUrls_bumpsImageVersion() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(
                        sourceRow(
                            "Azora",
                            baseUrl = "https://azora.test",
                            imageBaseUrl = "https://oldimg.azora.test",
                            imageUrlVersion = 1,
                        ),
                    ),
                )
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(
                            1,
                            "Azora",
                            "https://azora.test/manga/1",
                            imageUrl = "https://oldimg.azora.test/c/1.jpg",
                        ),
                    ),
                )
            val document = doc(cfg("Azora", "https://azora.test", imageBase = "https://newimg.azora.test"))

            repo(document, sources, manga = manga).syncFromConfig()

            // cover migrated against the new IMAGE host; page URL untouched
            assertEquals("https://newimg.azora.test/c/1.jpg", manga.rows.single().imageUrl)
            assertEquals("https://azora.test/manga/1", manga.rows.single().url)
            // image base + version updated (1 → 2); page base untouched
            assertEquals(Triple("Azora", "https://newimg.azora.test", 2), sources.imageBaseUpdates.single())
            assertEquals(0, sources.baseUrlUpdates.size)
        }

    // --- Phase 5/6: legacy isolation (force-disable non-config rows) ------------------------------

    @Test
    fun forceDisables_enabled_legacy_rows_and_keeps_config_rows() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test", isEnabled = true), // config-backed, enabled
                        sourceRow("LegacyOnly", baseUrl = "https://legacy.test", isEnabled = true), // legacy, enabled
                        sourceRow("LegacyOff", baseUrl = "https://off.test", isEnabled = false), // legacy, already off
                    ),
                )
            // Only Azora is a config-backed (engine="generic") source in the active document.
            val document = doc(cfg("Azora", "https://azora.test"))

            repo(document, sources).syncFromConfig()

            // The enabled legacy row is force-disabled; the already-off legacy row is NOT re-written;
            // the config-backed row's enabled state is preserved.
            assertEquals(listOf("LegacyOnly" to false), sources.enabledCalls)
            assertEquals(false, sources.current().single { it.name == "LegacyOnly" }.isEnabled)
            assertEquals(true, sources.current().single { it.name == "Azora" }.isEnabled)
        }

    @Test
    fun validEmptyCatalog_disablesEveryPreviouslyEnabledRow() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("LegacyOnly", baseUrl = "https://legacy.test", isEnabled = true)),
                )

            repo(doc(), sources).syncFromConfig()

            assertEquals(listOf("LegacyOnly" to false), sources.enabledCalls)
            assertEquals(false, sources.current().single { it.name == "LegacyOnly" }.isEnabled)
        }

    // --- SourceRegistry retirement Phase 3: the sync owns the retired endpoint's behaviors -------

    @Test
    fun configSiteState_isProjectedIntoTheRow_andSteadyStateWritesNothing() =
        runTest {
            val sources = StatefulSourcesDao(listOf(sourceRow("Azora", baseUrl = "https://azora.test")))
            val document = doc(cfg("Azora", "https://azora.test").copy(siteState = "UNDER_MAINTENANCE"))

            repo(document, sources).syncFromConfig()
            assertEquals(listOf("Azora" to SourceState.UNDER_MAINTENANCE), sources.siteStateUpdates)
            assertEquals(SourceState.UNDER_MAINTENANCE, sources.current().single().siteState)

            repo(document, sources).syncFromConfig() // steady state: already projected → zero writes
            assertEquals(1, sources.siteStateUpdates.size)
        }

    @Test
    fun lifecycleDisabled_forceDisablesTheRow_everySync_andKeepsIt() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://azora.test", isEnabled = true)),
                )
            val document = doc(cfg("Azora", "https://azora.test").copy(lifecycle = "disabled"))

            repo(document, sources).syncFromConfig()

            assertEquals(listOf("Azora" to false), sources.enabledCalls)
            assertEquals(false, sources.current().single().isEnabled)
            assertEquals(emptyList(), sources.deletes) // the row is kept for saved-entry reads
        }

    @Test
    fun lifecycleRemoved_deletesTheRow_neverReseeds_andLeavesTheLibraryAlone() =
        runTest {
            val sources = StatefulSourcesDao(listOf(sourceRow("Azora", baseUrl = "https://azora.test")))
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://azora.test/manga/1")))
            val document = doc(cfg("Azora", "https://azora.test").copy(lifecycle = "removed"))

            repo(document, sources, manga = manga).syncFromConfig()
            assertEquals(listOf("Azora"), sources.deletes)
            assertTrue(sources.current().isEmpty())
            assertEquals(0, manga.updates.size) // saved-library rows untouched (endpoint parity)
            assertEquals("https://azora.test/manga/1", manga.rows.single().url)

            repo(document, sources, manga = manga).syncFromConfig() // and it never comes back
            assertEquals(0, sources.inserts.size)
        }

    @Test
    fun previousHosts_sweep_rewritesOnlyDeclaredOldHosts_andIsIdempotent() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://azora.test")), // row already current
                )
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Azora", "https://ancient.azora.old/manga/1"), // declared previous host
                        mangaRow(2, "Azora", "https://azora.test/manga/2"), // already current
                        mangaRow(3, "Azora", "https://my-mirror.example/manga/3"), // undeclared → untouched
                    ),
                )
            val document = doc(cfg("Azora", "https://azora.test").copy(previousHosts = listOf("ancient.azora.old")))

            repo(document, sources, manga = manga).syncFromConfig()

            assertEquals("https://azora.test/manga/1", manga.rows.first { it.id == 1L }.url)
            assertEquals("https://azora.test/manga/2", manga.rows.first { it.id == 2L }.url)
            assertEquals("https://my-mirror.example/manga/3", manga.rows.first { it.id == 3L }.url)
            assertEquals(1, manga.updates.size) // only the declared-old-host row was written
            assertEquals(0, sources.baseUrlUpdates.size) // the row itself was already current

            repo(document, sources, manga = manga).syncFromConfig()
            assertEquals(1, manga.updates.size) // second sync writes nothing (idempotent)
        }

    @Test
    fun previousImageHosts_sweep_movesCoversToTheImageBase() =
        runTest {
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://azora.test", imageBaseUrl = "https://img.azora.test")),
                )
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(
                            1,
                            "Azora",
                            "https://azora.test/manga/1",
                            imageUrl = "https://oldimg.azora.net/c/1.jpg",
                        ),
                    ),
                )
            val document =
                doc(
                    cfg("Azora", "https://azora.test", imageBase = "https://img.azora.test")
                        .copy(previousImageHosts = listOf("oldimg.azora.net")),
                )

            repo(document, sources, manga = manga).syncFromConfig()

            assertEquals("https://img.azora.test/c/1.jpg", manga.rows.single().imageUrl)
            assertEquals("https://azora.test/manga/1", manga.rows.single().url) // page URL untouched
            assertEquals(0, sources.imageBaseUpdates.size) // row image base was already current
        }

    @Test
    fun userEditedMirrorRow_survivesTheSync_andDeclaredHostMovesStillApply() =
        runTest {
            // The repo-settings screen persists a user-edited mirror into sources.baseUrl. The sync
            // must NOT revert a host config does not know (≠ config.baseUrl, ∉ previousHosts)…
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://my-mirror.example", isEnabled = true)),
                )
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://my-mirror.example/manga/1")))
            val document = doc(cfg("Azora", "https://azora.test").copy(previousHosts = listOf("azora.old")))

            repo(document, sources, manga = manga).syncFromConfig()

            assertEquals(0, sources.baseUrlUpdates.size)
            assertEquals("https://my-mirror.example", sources.current().single().baseUrl)
            assertEquals(0, manga.updates.size) // and no indiscriminate stored-URL migration either

            // …while a row on a DECLARED previous host is config-owned and is still asserted over.
            val owned = StatefulSourcesDao(listOf(sourceRow("Azora", baseUrl = "https://azora.old", baseVersion = 1)))
            repo(document, owned).syncFromConfig()
            assertEquals(Triple("Azora", "https://azora.test", 2), owned.baseUrlUpdates.single())
        }

    @Test
    fun enabledSemantics_firstSeedHonorsConfig_userToggleOwnsItAfterwards() =
        runTest {
            // First seed: config.enabled=true seeds an enabled row…
            val fresh = StatefulSourcesDao()
            repo(doc(cfg("Azora", "https://azora.test").copy(enabled = true)), fresh).syncFromConfig()
            assertEquals(true, fresh.inserts.single().isEnabled)

            // …the default (absent field) seeds disabled — the classic opt-in posture…
            val classic = StatefulSourcesDao()
            repo(doc(cfg("Azora", "https://azora.test")), classic).syncFromConfig()
            assertEquals(false, classic.inserts.single().isEnabled)

            // …and an EXISTING row the user disabled is never re-enabled by config.
            val existing =
                StatefulSourcesDao(
                    listOf(sourceRow("Azora", baseUrl = "https://azora.test", isEnabled = false)),
                )
            repo(doc(cfg("Azora", "https://azora.test").copy(enabled = true)), existing).syncFromConfig()
            assertEquals(emptyList(), existing.enabledCalls)
            assertEquals(false, existing.current().single().isEnabled)
        }

    @Test
    fun legacyStanza_managesItsRow_withoutSeeding_andStaysOutOfTheActiveCatalog() =
        runTest {
            val document =
                doc(
                    cfg("Azora", "https://azora.test"), // generic pilot keeps the force-disable guard armed
                    cfg("MangaLek", "https://lekmanga.net", engine = "legacy")
                        .copy(siteState = "STOPPED", previousHosts = listOf("manga-lek.net")),
                )

            // No row → a metadata-only legacy stanza seeds NOTHING…
            val fresh = StatefulSourcesDao()
            repo(document, fresh).syncFromConfig()
            assertEquals(listOf("Azora"), fresh.inserts.map { it.name })

            // …an existing legacy row gets the full metadata treatment (siteState + alias sweep)…
            val sources =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test"),
                        sourceRow("MangaLek", baseUrl = "https://lekmanga.net", isEnabled = true),
                    ),
                )
            val manga = StatefulMangaDao(listOf(mangaRow(1, "MangaLek", "https://manga-lek.net/manga/1")))
            repo(document, sources, manga = manga).syncFromConfig()
            assertEquals(listOf("MangaLek" to SourceState.STOPPED), sources.siteStateUpdates)
            assertEquals("https://lekmanga.net/manga/1", manga.rows.single().url)

            // …and it is still not user-facing: the legacy-isolation pass force-disabled it (an
            // engine="legacy" stanza is deliberately NOT in the generic key set).
            assertEquals(listOf("MangaLek" to false), sources.enabledCalls)
        }

    // --- 2026-07 audit: the host-move assert is scoped to URLs on the OLD row host ----------------

    @Test
    fun baseUrlChange_rewritesOnlyOldRowHostUrls_offHostStoredUrlsSurvive() =
        runTest {
            // Mangabuddy shape: page rows live on the row's base host, but some stored URLs sit on a
            // DIFFERENT host (api subdomain / leftovers from a user-mirror session). A host move
            // must rewrite only the old-row-host URLs — blanket-rewriting the off-host ones would
            // corrupt them silently and permanently.
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("Mangabuddy", baseUrl = "https://mangak.io", isEnabled = true)),
                )
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Mangabuddy", "https://mangak.io/title/1"),
                        mangaRow(2, "Mangabuddy", "https://api.mangak.io/api/title/2"),
                    ),
                )
            val document = doc(cfg("Mangabuddy", "https://mangabuddy.com"))

            repo(document, sources, manga = manga).syncFromConfig()

            // On-host URL migrated…
            assertEquals("https://mangabuddy.com/title/1", manga.rows.first { it.id == 1L }.url)
            // …off-host URL untouched…
            assertEquals("https://api.mangak.io/api/title/2", manga.rows.first { it.id == 2L }.url)
            // …and the row itself asserted to the config truth.
            assertEquals(Triple("Mangabuddy", "https://mangabuddy.com", 1), sources.baseUrlUpdates.single())
        }

    @Test
    fun pathBearingBaseMove_swatMangaShape_noPathDoubling() =
        runTest {
            // SwatManga shape: the base carries a path prefix (/v2/api/v1) that every stored URL
            // already embeds. A host move must produce <newHost>/v2/api/v1/… exactly once.
            val sources =
                StatefulSourcesDao(
                    listOf(sourceRow("SwatManga", baseUrl = "https://old.swat.test/v2/api/v1")),
                )
            val manga =
                StatefulMangaDao(
                    listOf(mangaRow(1, "SwatManga", "https://old.swat.test/v2/api/v1/series/9")),
                )
            val document = doc(cfg("SwatManga", "https://new.swat.test/v2/api/v1"))

            repo(document, sources, manga = manga).syncFromConfig()

            assertEquals("https://new.swat.test/v2/api/v1/series/9", manga.rows.single().url)
            assertEquals(
                Triple("SwatManga", "https://new.swat.test/v2/api/v1", 1),
                sources.baseUrlUpdates.single(),
            )
        }
}
