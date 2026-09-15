package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceCatalogSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as SourceSelectionStore

@OptIn(ExperimentalCoroutinesApi::class)
class AcceptedSourceCatalogReactivityTest {
    @Test
    fun liveCollectorsFollowAdditionsAndReactivationInEitherPublicationOrder() = runTest {
        listOf(true, false).forEach { roomFirst ->
            val fixture = CatalogProjectionFixture(backgroundScope, listOf(row("a")), catalog(1, "a"))
            runCurrent()
            fixture.assertApis(listOf("a"))
            val replacement = catalog(2, "a", "b")
            if (roomFirst) fixture.dao.insert(row("b")) else fixture.registry.catalog.value = replacement
            runCurrent()
            fixture.assertApis(listOf("a"))
            if (roomFirst) fixture.registry.catalog.value = replacement else fixture.dao.insert(row("b"))
            runCurrent()
            fixture.assertApis(listOf("a", "b"))
            val rows = fixture.dao.current()
            fixture.registry.catalog.value = catalog(3, "a")
            runCurrent()
            fixture.assertApis(listOf("a"))
            fixture.registry.catalog.value = replacement.copy(revision = 4)
            runCurrent()
            fixture.assertApis(listOf("a", "b"))
            assertEquals(rows, fixture.dao.current(), "catalog-only reactivation must need no repair write")
            assertEquals(listOf("b"), fixture.dao.inserts.map { it.name })
            assertTrue(fixture.dao.enabledCalls.isEmpty())
        }
    }

    @Test
    fun acceptedMetadataOrderAndSiteStatePreserveUserChoicesWithoutRoomRepair() = runTest {
        val seed = staleRows()
        val fixture = CatalogProjectionFixture(backgroundScope, seed, catalog(1, "alpha", "beta", "off"), "beta")
        runCurrent()
        fixture.registry.catalog.value = renamedCatalog()
        runCurrent()
        assertRenamedProjection(fixture)
        assertEquals(seed, fixture.dao.current(), "metadata publication must not rewrite Room user choices")
        fixture.home.selectTab(0)
        assertEquals("beta", fixture.selection.activeApiFlow.value)
        fixture.sources.setLanguageEnabled("(AR)", true)
        assertTrue(fixture.dao.enabledCalls.isEmpty(), "bulk selection uses accepted language, not stale Room")
        fixture.sources.setLanguageEnabledWithFallback("(UNKNOWN)", "(JA)", true)
        runCurrent()
        assertEquals(listOf("off", "beta", "alpha").map { it to true }, fixture.dao.enabledCalls)
        fixture.assertApis(listOf("off", "beta", "alpha"))
        fixture.registry.catalog.value = catalog(3)
        runCurrent()
        fixture.assertApis(emptyList())
        assertEquals(SiteState.STOPPED, fixture.siteStates.last())
        fixture.sources.setLanguageEnabled("(JA)", false)
        assertEquals(3, fixture.dao.enabledCalls.size, "an empty accepted catalog has no toggle targets")
        assertEquals(false, fixture.dao.current().single { it.name == "hidden" }.isEnabled)
    }
}

private class CatalogProjectionFixture(
    scope: CoroutineScope,
    seed: List<SourcesEntity>,
    initial: SourceCatalogSnapshot,
    watchedApi: String = "a",
) {
    val dao = StatefulSourcesDao(seed)
    val registry = ObservableProjectionRegistry(initial)
    val selection = SourceSelectionStore(dao, emptySet(), SharedPrefsHelper(MapSettings()), scope)
    val sources = SourcesRepositoryImpl(selection, registry, DataStoreHelper(MapSettings()))
    val home = HomeFeedRepositoryImpl(selection, testDispatchers, registry)
    val sourceEmissions = mutableListOf<List<Source>>()
    val homeEmissions = mutableListOf<List<SourceTab>>()
    val siteStates = mutableListOf<SiteState>()

    init {
        scope.launch { sources.observeSources().toList(sourceEmissions) }
        scope.launch { home.observeSourceTabs().toList(homeEmissions) }
        scope.launch { home.observeSiteState(watchedApi).toList(siteStates) }
    }

    fun assertApis(apis: List<String>) {
        assertEquals(apis, sourceEmissions.last().map { it.api })
        assertEquals(apis, homeEmissions.last().map { it.api })
    }
}

/** Projection code must consume the emitted snapshot, not consult mutable imperative lookups. */
private class ObservableProjectionRegistry(initial: SourceCatalogSnapshot) : SourceRegistry {
    override val catalog = MutableStateFlow(initial)

    override fun get(api: String): MangaSourceClient? = error("No client needed for catalog projection")
    override fun isConfigBacked(api: String): Boolean = error("Use the emitted catalog")
    override fun descriptor(api: String): RuntimeSourceDescriptor? = error("Use the emitted catalog")
    override fun genericDescriptors(): List<RuntimeSourceDescriptor> = error("Use the emitted catalog")
}

private fun catalog(revision: Long, vararg apis: String): SourceCatalogSnapshot =
    SourceCatalogSnapshot(revision, apis.map(::fakeDescriptor))

private fun row(api: String): SourcesEntity = sourceRow(api, baseUrl = "https://$api.test")

private fun staleRows(): List<SourcesEntity> = listOf(
    row("beta").copy(priority = 0, baseUrl = "https://user-mirror.test"),
    row("hidden").copy(priority = 1, isEnabled = false),
    row("off").copy(priority = 2, isEnabled = false),
    row("alpha").copy(priority = 3, baseUrl = "about:about"),
)

private fun renamedCatalog(): SourceCatalogSnapshot = SourceCatalogSnapshot(
    revision = 2,
    descriptors = listOf("off", "beta", "alpha").mapIndexed { index, api ->
        fakeDescriptor(api, language = "(JA)").copy(
            displayName = "new-$api",
            baseUrl = "https://new-$api.test",
            priority = 100 - index, // Neither this reversed config priority nor Room priority owns order.
            enabledByDefault = true,
            siteState = "UNDER_MAINTENANCE",
        )
    },
)

private fun assertRenamedProjection(fixture: CatalogProjectionFixture) {
    val sources = fixture.sourceEmissions.last()
    val tabs = fixture.homeEmissions.last()
    assertEquals(listOf("off", "beta", "alpha"), sources.map { it.api })
    assertEquals(listOf(0, 1, 2), sources.map { it.priority })
    assertEquals(listOf(false, true, true), sources.map { it.isEnabled })
    assertEquals(listOf("(JA)", "(JA)", "(JA)"), sources.map { it.language })
    assertEquals(listOf("beta", "alpha"), tabs.map { it.api })
    assertEquals(listOf("new-beta", "new-alpha"), tabs.map { it.displayName })
    assertEquals(listOf("(JA)", "(JA)"), tabs.map { it.language })
    assertTrue(tabs.all { it.siteState == SiteState.UNDER_MAINTENANCE })
    assertEquals("https://user-mirror.test", tabs[0].baseUrl)
    assertEquals("https://new-alpha.test", tabs[1].baseUrl)
    assertEquals(listOf(SiteState.WORKING, SiteState.UNDER_MAINTENANCE), fixture.siteStates)
    assertEquals(
        listOf(listOf("alpha", "beta", "off"), listOf("new-off", "new-beta", "new-alpha")),
        fixture.sourceEmissions.map { emission -> emission.map { it.displayName } },
    )
}
