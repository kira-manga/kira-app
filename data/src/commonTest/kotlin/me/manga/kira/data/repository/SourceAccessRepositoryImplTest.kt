package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.sources.SourceAccessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAccessRepositoryImplTest {
    @Test
    fun onboarding_completion_does_not_grandfather_source_access() {
        val settings = MapSettings().apply { putBoolean("first_launch", false) }

        assertEquals(SourceAccessState.LOCKED, SourceAccessRepositoryImpl(settings).state.value)
    }

    @Test
    fun activation_survives_recreation_and_only_writes_version_one_once() =
        runTest {
            val settings = MapSettings()
            val repository = SourceAccessRepositoryImpl(settings)

            assertEquals(SourceAccessState.LOCKED, repository.state.value)
            assertTrue(repository.activatePermanently())
            assertEquals(1, settings.getInt("source_access_version", 0))
            assertEquals(SourceAccessState.ACTIVATED, repository.state.value)
            assertFalse(repository.activatePermanently())
            assertEquals(SourceAccessState.ACTIVATED, SourceAccessRepositoryImpl(settings).state.value)
        }
}
