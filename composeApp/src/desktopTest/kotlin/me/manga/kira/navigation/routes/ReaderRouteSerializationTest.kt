package me.manga.kira.navigation.routes

import androidx.navigation.serialization.decodeArguments
import androidx.navigation.serialization.generateNavArguments
import androidx.navigation.serialization.generateRouteWithArgs
import androidx.savedstate.savedState
import kotlinx.serialization.ExperimentalSerializationApi
import me.manga.kira.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses Navigation 2.9.2's Kotlin-public, library-restricted APIs only as a pinned test seam. */
class ReaderRouteSerializationTest {
    private val serializer = Screen.ChapterImagesFragment.serializer()
    private val types = serializer.generateNavArguments().associate { it.name to it.argument.type }

    @Test
    fun largePersistedListsDoNotIncreaseRealNavigationPayload() {
        val paths = loosePagePathsForReaderRoute()
        val history = historyEntryForReaderRoute(paths)
        val update = updateEntryForReaderRoute(paths)
        val routePairs =
            listOf(
                history.toReaderRoute() to history.copy(localImagePaths = emptyList()).toReaderRoute(),
                update.toReaderRoute() to update.copy(localImagePaths = emptyList()).toReaderRoute(),
            )

        routePairs.forEach { (largeManifestRoute, emptyManifestRoute) ->
            val emptyPayload = generateRouteWithArgs(emptyManifestRoute, types)
            val largePayload = generateRouteWithArgs(largeManifestRoute, types)

            assertEquals(emptyPayload, largePayload)
            assertFalse(largePayload.contains("paths="))

            // Positive control: the unchanged legacy slot still serializes populated lists.
            val legacy = emptyManifestRoute.copy(paths = listOf(paths.first(), paths.last()))
            val legacyPayload = generateRouteWithArgs(legacy, types)
            assertTrue(legacyPayload.contains("paths="))
            assertTrue(legacyPayload.length > emptyPayload.length)
        }
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun legacySavedArgumentsPreserveIdentityAndPaths() {
        val legacy =
            historyEntryForReaderRoute().toReaderRoute().copy(
                isHome = true,
                paths = listOf("/legacy/first.webp", "/legacy/second page.webp"),
            )
        val values =
            mapOf(
                "route" to legacy.route,
                "isHome" to legacy.isHome,
                "api" to legacy.api,
                "language" to legacy.language,
                "mangaId" to legacy.mangaId,
                "chapterId" to legacy.chapterId,
                "mangatitle" to legacy.mangatitle,
                "mangaUrl" to legacy.mangaUrl,
                "mangaImgUrl" to legacy.mangaImgUrl,
                "chapterNumber" to legacy.chapterNumber,
                "chapterUrl" to legacy.chapterUrl,
                "paths" to legacy.paths,
                "isDownload" to legacy.isDownload,
            )
        assertEquals(types.keys, values.keys, "Populate every serialized field, including inherited route")
        val state = savedState(emptyMap())
        values.forEach { (name, value) -> types.getValue(name).put(state, name, value) }

        val restored = serializer.decodeArguments(state, types)

        assertEquals(legacy, restored)
        assertEquals(legacy.route, restored.route, "Data-class equality does not compare inherited route")
        val pathsIndex = serializer.descriptor.getElementIndex("paths")
        assertTrue(pathsIndex >= 0)
        assertTrue(serializer.descriptor.getElementDescriptor(pathsIndex).isNullable)
        assertFalse(serializer.descriptor.isElementOptional(pathsIndex))
        // This is typed argument decoding, not NavController/Activity or process-death restoration.
    }
}
