package me.manga.kira.navigation.routes

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Actual Share menu -> intent -> VM effect -> both real route adapters -> existing platform port. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DetailsShareRouteTest {
    @Test
    fun fullTupleRouteSharesResolvedPayloadOnceAcrossRemount() = routeTest(urlOnly = false)

    @Test
    fun urlOnlyRouteSharesResolvedPayloadOnceAcrossRemount() = routeTest(urlOnly = true)

    private fun routeTest(urlOnly: Boolean) =
        withEnglishLocale {
            runComposeUiTest {
                runOnUiThread { Dispatchers.setMain(UnconfinedTestDispatcher(mainClock.scheduler)) }
                try {
                    val fixture = runOnUiThread { DetailsShareRouteFixture(urlOnly) }
                    try {
                        setContent { fixture.Content() }
                        awaitIdle()
                        assertShareAndRemount(fixture, urlOnly)
                    } finally {
                        try {
                            runOnUiThread { fixture.mounted = false }
                            awaitIdle()
                        } finally {
                            runOnUiThread { fixture.close() }
                        }
                    }
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    private suspend fun ComposeUiTest.assertShareAndRemount(
        fixture: DetailsShareRouteFixture,
        urlOnly: Boolean,
    ) {
        val source = fixture.source
        runOnIdle {
            assertReady(fixture)
            val requested = source.requests.single()
            assertEquals(if (urlOnly) "" else source.seed.title, requested.title)
            assertEquals(source.seed.url, requested.url)
            assertTrue(fixture.shares.isEmpty())
        }
        onNodeWithContentDescription(MORE_OPTIONS).assertIsDisplayed().performClick()
        onNodeWithText(SHARE).assertIsDisplayed().performClick()
        awaitIdle()
        onNodeWithText(SHARE).assertDoesNotExist()
        val expected = listOf(source.resolved.url to source.resolved.title)
        runOnIdle {
            assertEquals(expected, fixture.shares)
            fixture.compositionEpoch++
        }
        awaitIdle()
        onNodeWithContentDescription(MORE_OPTIONS).assertIsDisplayed()
        runOnIdle {
            assertReady(fixture)
            assertEquals(fixture.compositionEpoch, fixture.renderedEpoch)
            assertEquals(1, fixture.createdViewModels.size, "The route remount must retain its actual VM")
            assertEquals(1, source.requests.size, "The same-entry remount must not refetch")
            assertEquals(expected, fixture.shares, "Reattaching the collector must not replay Share")
        }
    }

    private fun assertReady(fixture: DetailsShareRouteFixture) {
        val source = fixture.source
        val state =
            fixture.createdViewModels
                .single()
                .state.value
        assertNotEquals(source.seed.title, source.resolved.title, "Keep the previously failing renamed-title route witness")
        assertNotEquals(source.seed.url, source.resolved.url, "Share must not substitute the requested address")
        assertEquals(source.resolved, state.details)
        assertEquals(source.resolved.title, state.manga?.title)
        assertEquals(source.seed.url, state.manga?.url)
        assertEquals(source.owner, state.savedOwner)
        assertEquals(listOf(source.owner.locator), source.library.observedKeys)
        assertEquals(source.owner, source.library.refreshRequests.single().owner)
        assertEquals(source.owner.locator, source.library.refreshRequests.single().fetched.requested)
        assertEquals(source.resolved, source.library.refreshRequests.single().fetched.details)
        assertTrue(source.reads.savedObservations.isNotEmpty())
        assertEquals(setOf(source.owner.locator), source.reads.savedObservations.toSet())
        assertEquals(listOf(source.owner.locator), source.reads.downloadObservations)
        assertFalse(state.isLoading)
        assertTrue(state.isInLibrary)
        assertFalse(state.isAdultGateActive)
    }

    private companion object {
        const val MORE_OPTIONS = "More options"
        const val SHARE = "Share"
    }
}

private fun withEnglishLocale(block: () -> Unit) {
    val previous = Locale.getDefault()
    try {
        Locale.setDefault(Locale.US)
        block()
    } finally {
        Locale.setDefault(previous)
    }
}
