package me.manga.kira.domain.usecase.sourceaccess

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.model.sources.SourceActivationLinkValidator
import me.manga.kira.domain.model.sources.SourceActivationResult
import me.manga.kira.domain.repository.SourceAccessRepository
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase
import me.manga.kira.domain.usecase.sources.SetLanguageEnabledUseCase
import me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAccessUseCaseTest {
    private class FakeSourceAccessRepository(
        initial: SourceAccessState,
    ) : SourceAccessRepository {
        override val state = MutableStateFlow(initial)
        var activationWrites = 0

        override suspend fun activatePermanently(): Boolean {
            if (state.value == SourceAccessState.ACTIVATED) return false
            activationWrites++
            state.value = SourceAccessState.ACTIVATED
            return true
        }
    }

    private class RecordingSourcesRepository : SourcesRepository {
        val writes = mutableListOf<String>()

        override fun observeSources(): Flow<List<Source>> = flowOf(emptyList())

        override suspend fun setSourceEnabled(
            api: String,
            enabled: Boolean,
        ) {
            writes += "source:$api:$enabled"
        }

        override suspend fun setLanguageEnabled(
            language: String,
            enabled: Boolean,
        ) {
            writes += "language:$language:$enabled"
        }

        override suspend fun setLanguageEnabledWithFallback(
            primary: String,
            fallback: String,
            enabled: Boolean,
        ) {
            writes += "fallback:$primary:$fallback:$enabled"
        }

        override fun observeHasNewSources(): Flow<Boolean> = flowOf(false)

        override suspend fun setHasNewSources(value: Boolean) = Unit
    }

    @Test
    fun validator_is_trimmed_case_insensitive_and_intentionally_permissive() {
        assertTrue(SourceActivationLinkValidator.isValid("  HTTPS://KIRAMANGA.ME/activate  "))
        assertTrue(SourceActivationLinkValidator.isValid("https://kiramanga.evil.com/activate"))
        assertFalse(SourceActivationLinkValidator.isValid("https://example.com/activate"))
        assertFalse(SourceActivationLinkValidator.isValid("   "))
    }

    @Test
    fun activation_is_permanent_and_persisted_only_once() =
        runTest {
            val repository = FakeSourceAccessRepository(SourceAccessState.LOCKED)
            val activate = ActivateSourceAccessUseCase(repository)

            assertEquals(SourceActivationResult.ACTIVATED, activate(" kiramanga://activate "))
            assertEquals(SourceActivationResult.ALREADY_ACTIVATED, activate("KIRAMANGA"))
            assertEquals(1, repository.activationWrites)
            assertEquals(SourceAccessState.ACTIVATED, repository.state.value)
        }

    @Test
    fun invalid_link_never_writes_activation() =
        runTest {
            val repository = FakeSourceAccessRepository(SourceAccessState.LOCKED)

            assertEquals(
                SourceActivationResult.INVALID_LINK,
                ActivateSourceAccessUseCase(repository)("not an activation link"),
            )
            assertEquals(0, repository.activationWrites)
            assertEquals(SourceAccessState.LOCKED, repository.state.value)
        }

    @Test
    fun locked_state_rejects_every_source_management_mutation() =
        runTest {
            val sources = RecordingSourcesRepository()
            val access = FakeSourceAccessRepository(SourceAccessState.LOCKED)

            assertFalse(SetSourceEnabledUseCase(sources, access)("Azora", false))
            assertFalse(SetLanguageEnabledUseCase(sources, access)("(AR)", true))
            assertFalse(EnableDefaultLanguageSourcesUseCase(sources, access)("ar"))
            assertTrue(sources.writes.isEmpty())
        }

    @Test
    fun activated_state_preserves_existing_source_mutation_behavior() =
        runTest {
            val sources = RecordingSourcesRepository()
            val access = FakeSourceAccessRepository(SourceAccessState.ACTIVATED)

            assertTrue(SetSourceEnabledUseCase(sources, access)("Azora", false))
            assertTrue(SetLanguageEnabledUseCase(sources, access)("(AR)", true))
            assertTrue(EnableDefaultLanguageSourcesUseCase(sources, access)("ar"))
            assertEquals(
                listOf(
                    "source:Azora:false",
                    "language:(AR):true",
                    "fallback:(AR):(EN):true",
                ),
                sources.writes,
            )
        }
}
