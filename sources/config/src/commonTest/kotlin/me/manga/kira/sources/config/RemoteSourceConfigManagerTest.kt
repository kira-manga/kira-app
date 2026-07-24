package me.manga.kira.sources.config

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RemoteSourceConfigManagerTest {
    private fun <T> AppResult<T>.valueOrFail(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> fail("expected success, got $error")
        }

    private fun SourceConfigDocument.label(api: String) = sources.first { it.api == api }.displayName

    @Test
    fun active_is_bundled_before_any_refresh() {
        val store = FakeConfigStore(bundled = configJson(5, listOf(SourceJson("a", label = "bundled-a"))))
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

        assertEquals(5, manager.activeDocument().revision)
        assertEquals("bundled-a", manager.activeDocument().label("a"))
        assertEquals(UpdateState.Origin.BUNDLED, (manager.state.value as UpdateState.Active).source)
    }

    @Test
    fun cache_overrides_bundled_for_same_api() =
        runTest {
            val store =
                FakeConfigStore(
                    bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))),
                    cached = signedConfig(configJson(3, listOf(SourceJson("a", label = "cache-a"))), revision = 3),
                )
            val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

            val effective = manager.refresh().valueOrFail()
            assertEquals("cache-a", effective.label("a"))
            assertEquals(3, effective.revision)
            assertEquals(UpdateState.Origin.CACHE, (manager.state.value as UpdateState.Active).source)
        }

    @Test
    fun remote_is_not_fetched_when_disabled() =
        runTest {
            // No RemoteConfigSource wired -> Stage-0 default -> only bundled+cache.
            val store = FakeConfigStore(bundled = configJson(2, listOf(SourceJson("a", label = "bundled-a"))))
            val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

            val effective = manager.refresh().valueOrFail()
            assertEquals("bundled-a", effective.label("a"))
            assertEquals(0, store.writeCount)
        }

    @Test
    fun remote_with_bad_signature_is_ignored_and_not_cached() =
        runTest {
            val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))))
            val remote =
                RemoteConfigSource {
                    signedConfig(
                        configJson(9, listOf(SourceJson("a", label = "remote-a"))),
                        revision = 9,
                    )
                }
            val manager = RemoteSourceConfigManager(store, FakeVerifier(result = false), SchemaOnlyValidator(), remote)

            val effective = manager.refresh().valueOrFail()
            assertEquals("bundled-a", effective.label("a")) // remote rejected
            assertEquals(0, store.writeCount) // nothing cached
        }

    @Test
    fun remote_with_good_signature_wins_and_is_cached() =
        runTest {
            val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))))
            val remoteRaw = configJson(9, listOf(SourceJson("a", label = "remote-a")))
            val signed = signedConfig(remoteRaw, revision = 9)
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(result = true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { signed },
                )

            val effective = manager.refresh().valueOrFail()
            assertEquals("remote-a", effective.label("a"))
            assertEquals(9, effective.revision)
            assertEquals(1, store.writeCount)
            assertEquals(signed, store.lastWritten) // cache the exact envelope we verified
            assertEquals(UpdateState.Origin.REMOTE, (manager.state.value as UpdateState.Active).source)
        }

    @Test
    fun corrupt_cache_is_dropped_and_bundled_stays() =
        runTest {
            val store =
                FakeConfigStore(
                    bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))),
                    cached = signedConfig("{ this is not valid json", revision = 2),
                )
            val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

            val effective = manager.refresh().valueOrFail()
            assertEquals("bundled-a", effective.label("a"))
        }

    @Test
    fun cache_is_reverified_after_process_restart() =
        runTest {
            val store =
                FakeConfigStore(
                    bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))),
                    cached =
                        signedConfig(
                            configJson(2, listOf(SourceJson("a", label = "untrusted-cache"))),
                            revision = 2,
                        ),
                )
            val manager = RemoteSourceConfigManager(store, FakeVerifier(false), SchemaOnlyValidator())

            assertEquals("bundled-a", manager.refresh().valueOrFail().label("a"))
            assertEquals(UpdateState.Origin.BUNDLED, (manager.state.value as UpdateState.Active).source)
        }

    @Test
    fun unavailable_remote_keeps_the_verified_cache_active() =
        runTest {
            val cached = signedConfig(configJson(8, listOf(SourceJson("a", label = "cache-a"))), revision = 8)
            val store = FakeConfigStore(bundled = configJson(4, emptyList()), cached = cached)
            val rejected = mutableListOf<String>()
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { error("network detail must not escape") },
                ) { _, reasons -> rejected += reasons }

            assertEquals("cache-a", manager.refresh().valueOrFail().label("a"))
            assertEquals(UpdateState.Origin.CACHE, (manager.state.value as UpdateState.Active).source)
            assertTrue(rejected.single().contains("fetch failed"))
            assertTrue(rejected.none { it.contains("network detail") })
        }

    @Test
    fun signed_metadata_must_match_the_document_fields() =
        runTest {
            val raw =
                configJson(
                    revision = 9,
                    sources = listOf(SourceJson("a", label = "remote-a")),
                    generatedAt = "2026-07-17T00:00:00Z",
                )
            val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))))
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { signedConfig(raw, revision = 9) },
                )

            assertEquals("bundled-a", manager.refresh().valueOrFail().label("a"))
            assertEquals(0, store.writeCount)
        }

    @Test
    fun bundled_priority_pin_resists_remote_override() =
        runTest {
            val store =
                FakeConfigStore(
                    bundled =
                        configJson(
                            1,
                            listOf(SourceJson("a", priority = 100, label = "bundled-pinned")),
                        ),
                )
            val remoteRaw = configJson(9, listOf(SourceJson("a", priority = 0, label = "remote-a")))
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { signedConfig(remoteRaw, revision = 9) },
                )

            val effective = manager.refresh().valueOrFail()
            assertEquals("bundled-pinned", effective.label("a")) // higher-priority bundled source wins
        }

    @Test
    fun missing_bundled_falls_back_to_empty_then_cache_supplies_sources() =
        runTest {
            val store =
                FakeConfigStore(
                    bundled = null,
                    cached = signedConfig(configJson(2, listOf(SourceJson("a", label = "cache-a"))), revision = 2),
                )
            val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

            // Before refresh: empty floor (no bundled asset present).
            assertTrue(manager.activeDocument().sources.isEmpty())
            // After refresh: cache supplies the sources.
            val effective = manager.refresh().valueOrFail()
            assertEquals("cache-a", effective.label("a"))
        }

    @Test
    fun remote_must_be_strictly_newer_and_link_to_the_last_signed_document() =
        runTest {
            val cachedRaw = configJson(8, listOf(SourceJson("a", label = "cache-a")))
            val cached = signedConfig(cachedRaw, revision = 8, checksum = "checksum-8")
            val store = FakeConfigStore(bundled = configJson(4, emptyList()), cached = cached)
            val replay = signedConfig(configJson(8, listOf(SourceJson("a", label = "replay"))), revision = 8)
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { replay },
                )

            assertEquals("cache-a", manager.refresh().valueOrFail().label("a"))
            assertEquals(0, store.writeCount)

            val wrongLink =
                signedConfig(
                    configJson(9, listOf(SourceJson("a", label = "wrong"))),
                    revision = 9,
                    previousRevision = 7,
                    previousChecksum = "checksum-7",
                )
            val wrongLinkManager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { wrongLink },
                )
            assertEquals("cache-a", wrongLinkManager.refresh().valueOrFail().label("a"))
            assertEquals(0, store.writeCount)
        }

    @Test
    fun remote_may_skip_revisions_when_its_chain_does_not_rollback_past_cache() =
        runTest {
            val cached =
                signedConfig(
                    configJson(8, listOf(SourceJson("a", label = "cache-a"))),
                    revision = 8,
                    checksum = "checksum-8",
                )
            val store = FakeConfigStore(bundled = configJson(4, emptyList()), cached = cached)
            val remote =
                signedConfig(
                    configJson(11, listOf(SourceJson("a", label = "remote-a"))),
                    revision = 11,
                    previousRevision = 10,
                    previousChecksum = "checksum-10",
                )
            val manager =
                RemoteSourceConfigManager(
                    store,
                    FakeVerifier(true),
                    SchemaOnlyValidator(),
                    RemoteConfigSource { remote },
                )

            assertEquals("remote-a", manager.refresh().valueOrFail().label("a"))
            assertEquals(1, store.writeCount)
        }
}
