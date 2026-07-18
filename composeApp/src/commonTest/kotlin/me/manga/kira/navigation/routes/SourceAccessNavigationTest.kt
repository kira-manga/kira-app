package me.manga.kira.navigation.routes

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.sourceaccess.SourceActivationRequestRouter
import me.manga.kira.presentation.home.HomeState
import me.manga.kira.presentation.settings.SettingsDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAccessNavigationTest {
    @Test
    fun activation_router_validates_without_retaining_the_link() {
        val router = SourceActivationRequestRouter()

        assertFalse(router.submit("https://example.com"))
        assertFalse(router.pending.value)
        assertTrue(router.submit("  KIRAMANGA://activate  "))
        assertTrue(router.pending.value)
        router.consume()
        assertFalse(router.pending.value)
    }

    @Test
    fun settings_reveals_source_management_only_after_activation() {
        assertEquals(
            Screen.StartReading(onboarding = false),
            sourceManagementDestination(SourceAccessState.LOCKED),
        )
        assertEquals(
            Screen.RepoSettings(isFirstOpen = false),
            sourceManagementDestination(SourceAccessState.ACTIVATED),
        )
    }

    @Test
    fun settings_crash_diagnostics_route_is_fail_closed() {
        assertNull(
            settingsDestination(
                destination = SettingsDestination.CRASH_DIAGNOSTICS,
                sourceAccessState = SourceAccessState.LOCKED,
                crashDiagnosticsEnabled = false,
            ),
        )
        assertEquals(
            Screen.CrashDiagnostics,
            settingsDestination(
                destination = SettingsDestination.CRASH_DIAGNOSTICS,
                sourceAccessState = SourceAccessState.LOCKED,
                crashDiagnosticsEnabled = true,
            ),
        )
    }

    @Test
    fun home_fallback_requires_typed_no_source_error_and_empty_feed() {
        val noSources = HomeState(feedError = AppError.Validation.NoEnabledSources())

        assertTrue(shouldShowNoSourceFallback(noSources))
        assertFalse(
            shouldShowNoSourceFallback(
                HomeState(feedError = AppError.Network.NoConnectivity()),
            ),
        )
        assertFalse(
            shouldShowNoSourceFallback(
                noSources.copy(feed = listOf(sampleFeedItem())),
            ),
        )
    }

    @Test
    fun only_successful_imports_launched_from_start_flow_complete_onboarding() {
        val success =
            BackupImportResult(
                mangasAdded = 1,
                mangasMerged = 0,
                chaptersAdded = 2,
                chaptersMerged = 0,
                downloadsRestored = 1,
                historyMerged = 0,
            )

        assertFalse(shouldCompleteStartFlowOnImport(requestedFromStartFlow = true, importResult = null))
        assertFalse(shouldCompleteStartFlowOnImport(requestedFromStartFlow = false, importResult = success))
        assertTrue(shouldCompleteStartFlowOnImport(requestedFromStartFlow = true, importResult = success))
    }

    private fun sampleFeedItem() =
        HomeFeedItem(
            api = "api",
            language = "en",
            title = "Existing",
            url = "https://example.com/manga",
            coverUrl = "",
            rating = null,
            genres = emptyList(),
            recentChapters = emptyList(),
        )
}
