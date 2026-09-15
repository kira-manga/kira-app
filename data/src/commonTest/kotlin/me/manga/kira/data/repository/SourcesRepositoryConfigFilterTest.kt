package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sources Migration — Phase 2. [SourcesRepositoryImpl] exposes ONLY config-backed (piloted) sources
 * to the UI; legacy-only sources stay hidden, and language bulk-toggles never enable a hidden source.
 * SourceRegistry retirement §2 (completed by the 2026-07 audit): a config stanza with
 * `lifecycle="disabled"` is additionally HIDDEN from the picker and never bulk-toggled — without
 * the hide, a user could re-enable a killed source each session (the sync only force-disables).
 * Runs against the production repository with its real legacy read facade and a [StatefulSourcesDao].
 * Real Room atomicity, ordering and cancellation are covered separately on Desktop.
 */
class SourcesRepositoryConfigFilterTest {
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
                backgroundScope.sourceSettingsRepository(
                    dao,
                    PilotRegistry(setOf("Azora", "Mangamello")),
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
                backgroundScope.sourceSettingsRepository(dao, PilotRegistry(setOf("Azora")))

            impl.setLanguageEnabled("(AR)", true)

            // only the piloted source was toggled; the hidden legacy source was never enabled
            assertEquals(listOf(listOf("Azora") to true), dao.bulkEnabledCalls)
            assertTrue(dao.enabledCalls.isEmpty(), "Bulk commands must not fan out single-source writes")
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
                backgroundScope.sourceSettingsRepository(
                    dao,
                    PilotRegistry(
                        setOf("Azora", "KilledSource"),
                        descriptors =
                            mapOf("KilledSource" to fakeDescriptor("KilledSource").copy(lifecycle = "disabled")),
                    ),
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
                backgroundScope.sourceSettingsRepository(
                    dao,
                    PilotRegistry(
                        setOf("Azora", "KilledSource"),
                        descriptors =
                            mapOf("KilledSource" to fakeDescriptor("KilledSource").copy(lifecycle = "disabled")),
                    ),
                )

            impl.setLanguageEnabled("(AR)", true)

            assertEquals(listOf(listOf("Azora") to true), dao.bulkEnabledCalls)
        }

    // --- MangaSource decoupling (2026-07): display metadata joins from the config descriptor ------

    @Test
    fun observeSources_joinsDisplayNameFromTheConfigDescriptor() =
        runTest {
            val dao = StatefulSourcesDao(listOf(sourceRow("Team X", baseUrl = "https://teamx.test")))
            val impl =
                backgroundScope.sourceSettingsRepository(
                    dao,
                    PilotRegistry(
                        setOf("Team X"),
                        descriptors =
                            mapOf("Team X" to fakeDescriptor("Team X").copy(displayName = "Team-X Scans")),
                    ),
                )

            val row = impl.observeSources().first().single()

            assertEquals("Team X", row.api, "api stays the stable key")
            assertEquals("Team-X Scans", row.displayName, "label comes from the stanza, not the api")
        }

    @Test
    fun setLanguageEnabledWithFallback_prefersEligiblePrimary() =
        runTest {
            val rows = listOf(toggleRow("French", "(FR)"), toggleRow("English", "(EN)"))
            val dao = StatefulSourcesDao(rows)
            val impl = backgroundScope.sourceSettingsRepository(dao, PilotRegistry(setOf("French", "English")))

            impl.setLanguageEnabledWithFallback("(FR)", "(EN)", true)

            assertEquals(listOf(listOf("French") to true), dao.bulkEnabledCalls)
            assertEquals(listOf(rows[0].copy(isEnabled = true), rows[1]), dao.current())
        }

    @Test
    fun setLanguageEnabledWithFallback_hiddenOnlyPrimaryUsesEnglish() =
        runTest {
            val rows = listOf(toggleRow("HiddenFrench", "(FR)"), toggleRow("English", "(EN)"))
            val dao = StatefulSourcesDao(rows)
            val registry =
                PilotRegistry(
                    setOf("HiddenFrench", "English"),
                    mapOf("HiddenFrench" to fakeDescriptor("HiddenFrench").copy(lifecycle = "disabled")),
                )
            val impl = backgroundScope.sourceSettingsRepository(dao, registry)

            impl.setLanguageEnabledWithFallback("(FR)", "(EN)", true)

            assertEquals(listOf(listOf("English") to true), dao.bulkEnabledCalls)
            assertEquals(listOf(rows[0], rows[1].copy(isEnabled = true)), dao.current())
        }

    @Test
    fun bulkSelectionRequiresActiveMembershipNotExecutableOrWorkingStatus() =
        runTest {
            val lifecycles = mapOf("Disabled" to "disabled", "Retired" to "retired", "Removed" to "removed")
            val rows =
                listOf(toggleRow("Stopped", "(EN)").copy(siteState = SourceState.STOPPED)) +
                    (lifecycles.keys + "Absent").map { toggleRow(it, "(EN)") }
            val descriptors =
                lifecycles.mapValues { (api, lifecycle) ->
                    fakeDescriptor(api).copy(lifecycle = lifecycle)
                }
            val registry =
                PilotRegistry(
                    lifecycles.keys + "Stopped",
                    descriptors + ("Stopped" to fakeDescriptor("Stopped").copy(siteState = "STOPPED")),
                    client = { error("Bulk selection must not resolve executable clients") },
                )
            val dao = StatefulSourcesDao(rows)
            val impl = backgroundScope.sourceSettingsRepository(dao, registry)

            impl.setLanguageEnabled("(EN)", true)

            assertEquals(listOf(listOf("Stopped") to true), dao.bulkEnabledCalls)
            assertEquals(rows.map { if (it.name == "Stopped") it.copy(isEnabled = true) else it }, dao.current())
        }

    @Test
    fun exactPersistedLanguageTagsAndEmptySelectionNeverWidenTheCommand() =
        runTest {
            val rows = listOf(toggleRow("English", "(EN)"))
            val dao = StatefulSourcesDao(rows)
            // Descriptor language differs deliberately: selection must use persisted tags.
            val impl = backgroundScope.sourceSettingsRepository(dao, PilotRegistry(setOf("English")))

            listOf("(en)", "EN", " (EN)", "(AR)").forEach { impl.setLanguageEnabled(it, true) }
            impl.setLanguageEnabledWithFallback("(FR)", "(DE)", true)

            assertTrue(dao.bulkEnabledCalls.isEmpty())
            assertTrue(dao.enabledCalls.isEmpty())
            assertEquals(rows, dao.current())
        }

    @Test
    fun singleSourcePreservesExactNameWithoutAddingACatalogFilter() =
        runTest {
            val row = toggleRow("HiddenEnglish", "(EN)")
            val dao = StatefulSourcesDao(listOf(row))
            val impl = backgroundScope.sourceSettingsRepository(dao, PilotRegistry(emptySet()))

            impl.setSourceEnabled("hiddenenglish", true)
            assertEquals(listOf(row), dao.current())
            impl.setSourceEnabled("HiddenEnglish", true)

            assertEquals(listOf(row.copy(isEnabled = true)), dao.current())
            assertTrue(dao.bulkEnabledCalls.isEmpty())
        }

    private fun toggleRow(
        api: String,
        language: String,
    ) = sourceRow(api, baseUrl = "https://$api.test", isEnabled = false, language = language)
}
