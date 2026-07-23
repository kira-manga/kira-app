package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SourceRegistry retirement Phase 3, §5(f): the push deep-link trust gate accepts exactly the
 * config-declared hosts for an api — baseUrl/imageBase hosts, previousHosts, previousImageHosts,
 * trustedHosts, and their subdomains — and rejects foreign hosts, other apis' hosts, and unknown
 * apis. [ConfigHostTrust] is consulted BEFORE the legacy host→repo resolver in `App.kt`'s
 * `ownsHostForApi`.
 */
class ConfigHostTrustTest {
    private class FakeUpdateManager(
        private val document: SourceConfigDocument,
    ) : SourceUpdateManager {
        private val _state =
            MutableStateFlow<UpdateState>(UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED))
        override val state: StateFlow<UpdateState> = _state.asStateFlow()

        override fun activeDocument(): SourceConfigDocument = document

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
    }

    private val trust =
        ConfigHostTrust(
            FakeUpdateManager(
                SourceConfigDocument(
                    schemaVersion = 1,
                    sources =
                        listOf(
                            SourceConfig(
                                api = "Azora",
                                language = "(AR)",
                                baseUrl = "https://azoramoon.com",
                                imageBase = "https://img.azora.net",
                                engine = "generic",
                                previousHosts = listOf("azoramoon.co"),
                                previousImageHosts = listOf("oldimg.azora.net"),
                                trustedHosts = listOf("cdn.azora-images.net"),
                            ),
                            // A metadata-only legacy stanza participates in trust exactly the same way.
                            SourceConfig(
                                api = "Other",
                                language = "en",
                                baseUrl = "https://other.test",
                                engine = "legacy",
                            ),
                        ),
                ),
            ),
        )

    @Test
    fun every_declared_host_family_is_owned() {
        assertTrue(trust.ownsHost("Azora", "azoramoon.com")) // baseUrl host
        assertTrue(trust.ownsHost("Azora", "img.azora.net")) // imageBase host
        assertTrue(trust.ownsHost("Azora", "azoramoon.co")) // previousHosts
        assertTrue(trust.ownsHost("Azora", "oldimg.azora.net")) // previousImageHosts
        assertTrue(trust.ownsHost("Azora", "cdn.azora-images.net")) // trustedHosts
        assertFalse(trust.ownsHost("Other", "other.test")) // legacy stanza never grants trust
    }

    @Test
    fun subdomains_are_owned_and_case_is_ignored() {
        assertTrue(trust.ownsHost("Azora", "media.azoramoon.com"))
        assertTrue(trust.ownsHost("Azora", "AzoraMoon.COM"))
        assertTrue(trust.ownsHost("Azora", "a.b.cdn.azora-images.net"))
    }

    @Test
    fun foreign_hosts_other_apis_hosts_superstrings_and_unknown_apis_are_rejected() {
        assertFalse(trust.ownsHost("Azora", "evil.example"))
        assertFalse(trust.ownsHost("Azora", "other.test")) // per-api isolation
        assertFalse(trust.ownsHost("Nope", "azoramoon.com")) // unknown api
        assertFalse(trust.ownsHost("Azora", "")) // blank host
        // A superstring host must not pass — only true subdomains (dot boundary) do.
        assertFalse(trust.ownsHost("Azora", "notazoramoon.com"))
    }
}
