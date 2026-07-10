package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.platform.storage.DataStoreHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/**
 * Sources Migration — Phase 2. [SourcesRepositoryImpl] exposes ONLY config-backed (piloted) sources
 * to the UI; legacy-only sources stay hidden, and language bulk-toggles never enable a hidden source.
 * SourceRegistry retirement §2 (completed by the 2026-07 audit): a config stanza with
 * `lifecycle="disabled"` is additionally HIDDEN from the picker and never bulk-toggled — without
 * the hide, a user could re-enable a killed source each session (the sync only force-disables).
 * Runs against the REAL legacy [LegacySourcesRepository] (the cell of truth) backed by an in-memory
 * [StatefulSourcesDao], so the filter is verified through the actual delegation path.
 */
class SourcesRepositoryConfigFilterTest {
    // U2: the impl now carries the new-sources badge cell; an in-memory settings map suffices.
    private fun testDataStore() = DataStoreHelper(MapSettings())

    private fun legacy(dao: StatefulSourcesDao) =
        LegacySourcesRepository(
            sourcesDao = dao,
            repos = emptySet(),
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun observeSources_returnsOnlyPilotedSources() =
        runTest {
            val dao =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test"),
                        sourceRow("LegacyOnly", baseUrl = "https://legacy.test"),
                        sourceRow("Mangamello", baseUrl = "https://mm.test"),
                    ),
                )
            val impl =
                SourcesRepositoryImpl(
                    legacy(dao),
                    PilotRegistry(setOf("Azora", "Mangamello")),
                    testDataStore(),
                )

            val visible =
                impl
                    .observeSources()
                    .first()
                    .map { it.api }
                    .toSet()

            assertEquals(setOf("Azora", "Mangamello"), visible)
        }

    @Test
    fun setLanguageEnabled_onlyTogglesPilotedSources() =
        runTest {
            val dao =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test", isEnabled = false, language = "(AR)"),
                        sourceRow("LegacyOnly", baseUrl = "https://legacy.test", isEnabled = false, language = "(AR)"),
                    ),
                )
            val impl =
                SourcesRepositoryImpl(legacy(dao), PilotRegistry(setOf("Azora")), testDataStore())

            impl.setLanguageEnabled("(AR)", true)

            // only the piloted source was toggled; the hidden legacy source was never enabled
            assertEquals(listOf("Azora" to true), dao.enabledCalls)
        }

    // --- SourceRegistry retirement §2: lifecycle="disabled" hides + excludes from bulk toggles ----

    @Test
    fun observeSources_hidesLifecycleDisabledSources() =
        runTest {
            val dao =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test"),
                        sourceRow("KilledSource", baseUrl = "https://killed.test"),
                    ),
                )
            val impl =
                SourcesRepositoryImpl(
                    legacy(dao),
                    PilotRegistry(
                        setOf("Azora", "KilledSource"),
                        descriptors =
                            mapOf("KilledSource" to fakeDescriptor("KilledSource").copy(lifecycle = "disabled")),
                    ),
                    testDataStore(),
                )

            // A disabled-lifecycle source never reaches the picker — the sync force-disables it
            // every launch, and hiding it closes the "user re-enables it each session" loophole.
            assertEquals(listOf("Azora"), impl.observeSources().first().map { it.api })
        }

    @Test
    fun setLanguageEnabled_neverTogglesLifecycleDisabledSources() =
        runTest {
            val dao =
                StatefulSourcesDao(
                    listOf(
                        sourceRow("Azora", baseUrl = "https://azora.test", isEnabled = false, language = "(AR)"),
                        sourceRow(
                            "KilledSource",
                            baseUrl = "https://killed.test",
                            isEnabled = false,
                            language = "(AR)",
                        ),
                    ),
                )
            val impl =
                SourcesRepositoryImpl(
                    legacy(dao),
                    PilotRegistry(
                        setOf("Azora", "KilledSource"),
                        descriptors =
                            mapOf("KilledSource" to fakeDescriptor("KilledSource").copy(lifecycle = "disabled")),
                    ),
                    testDataStore(),
                )

            impl.setLanguageEnabled("(AR)", true)

            assertEquals(listOf("Azora" to true), dao.enabledCalls)
        }

    // --- MangaSource decoupling (2026-07): display metadata joins from the config descriptor ------

    @Test
    fun observeSources_joinsDisplayNameFromTheConfigDescriptor() =
        runTest {
            val dao = StatefulSourcesDao(listOf(sourceRow("Team X", baseUrl = "https://teamx.test")))
            val impl =
                SourcesRepositoryImpl(
                    legacy(dao),
                    PilotRegistry(
                        setOf("Team X"),
                        descriptors =
                            mapOf("Team X" to fakeDescriptor("Team X").copy(displayName = "Team-X Scans")),
                    ),
                    testDataStore(),
                )

            val row = impl.observeSources().first().single()

            assertEquals("Team X", row.api, "api stays the stable key")
            assertEquals("Team-X Scans", row.displayName, "label comes from the stanza, not the api")
        }
}
