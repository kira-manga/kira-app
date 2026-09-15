package me.manga.kira.core.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.test.assertEquals

internal class NavigationBarNavigationFixture {
    lateinit var nav: NavHostController
    val disposedReaders = mutableListOf<String>()
    private val mountedReaders = linkedMapOf<String, String>()

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        val controller = rememberNavController()
        SideEffect { nav = controller }
        // Deliberately retain the production NavHost's default overlapping transitions.
        NavHost(navController = controller, startDestination = HOME) {
            composable(HOME) { Box(Modifier.fillMaxSize()) }
            composable(READER) { Reader(it) }
        }
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    private fun Reader(entry: NavBackStackEntry) {
        val chapter = requireNotNull(entry.arguments?.getString(CHAPTER))
        HideNavigationBarSideEffect()
        DisposableEffect(entry.id) {
            mountedReaders[entry.id] = chapter
            onDispose {
                mountedReaders.remove(entry.id)
                disposedReaders += chapter
            }
        }
        Box(Modifier.fillMaxSize())
    }

    fun openReader(chapter: String) {
        nav.navigate("reader/$chapter")
    }

    fun returnHome(): Boolean = nav.popBackStack(HOME, inclusive = false)

    fun assertMounted(chapters: List<String>) {
        assertEquals(chapters.sorted(), mountedReaders.values.sorted(), "actually composed Reader entries")
    }

    private companion object {
        const val HOME = "home"
        const val CHAPTER = "chapter"
        const val READER = "reader/{$CHAPTER}"
    }
}
