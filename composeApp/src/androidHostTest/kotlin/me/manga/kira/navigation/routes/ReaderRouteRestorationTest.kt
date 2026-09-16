package me.manga.kira.navigation.routes

import android.os.Bundle
import android.os.Parcel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import me.manga.kira.navigation.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android controller/Bundle restoration, without rendering UI or claiming OS process recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderRouteRestorationTest {
    @Test
    fun readerBackStackRestoresFromAndroidParcelWithoutPersistedPageLists() {
        val paths = loosePagePathsForReaderRoute()
        val routes =
            listOf(
                historyEntryForReaderRoute(paths).toReaderRoute(),
                updateEntryForReaderRoute(paths).toReaderRoute(),
            )
        val original = readerController()
        val before =
            routes.map { route ->
                assertNull(route.paths)
                original.navigate(route)
                val entry = assertNotNull(original.currentBackStackEntry)
                val decoded = entry.toRoute<Screen.ChapterImagesFragment>()
                // Navigation defaults an omitted collection argument to an empty collection.
                assertTrue(decoded.paths.isNullOrEmpty())
                assertEquals(route.copy(paths = decoded.paths), decoded)
                assertEquals(route.route, decoded.route)
                entry to decoded
            }

        val restored = readerController(parcelRoundTrip(assertNotNull(original.saveState())))
        assertNotSame(original, restored)
        before.asReversed().forEach { (oldEntry, expected) ->
            val entry = assertNotNull(restored.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<Screen.ChapterImagesFragment>())
            assertNotSame(oldEntry, entry)
            assertEquals(oldEntry.id, entry.id, "Restore the saved entry, not a newly navigated replacement")
            val decoded = entry.toRoute<Screen.ChapterImagesFragment>()
            assertEquals(expected, decoded)
            assertEquals(expected.route, decoded.route, "Data-class equality omits the inherited route")
            assertTrue(decoded.paths.isNullOrEmpty())
            assertTrue(assertNotNull(entry.arguments).getStringArray("paths").isNullOrEmpty())
            assertTrue(restored.popBackStack())
        }
        assertTrue(assertNotNull(restored.currentDestination).hasRoute<Screen.Library>())
        assertNull(restored.previousBackStackEntry)
    }

    private fun readerController(savedState: Bundle? = null): NavHostController =
        NavHostController(RuntimeEnvironment.getApplication()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            restoreState(savedState)
            graph =
                createGraph(startDestination = Screen.Library) {
                    composable<Screen.Library> {}
                    composable<Screen.ChapterImagesFragment> {}
                }
        }

    private fun parcelRoundTrip(state: Bundle): Bundle {
        val writer = Parcel.obtain()
        val bytes =
            try {
                writer.writeBundle(state)
                writer.marshall()
            } finally {
                writer.recycle()
            }
        val reader = Parcel.obtain()
        return try {
            reader.unmarshall(bytes, 0, bytes.size)
            reader.setDataPosition(0)
            assertNotNull(reader.readBundle(javaClass.classLoader))
        } finally {
            reader.recycle()
        }
    }
}
