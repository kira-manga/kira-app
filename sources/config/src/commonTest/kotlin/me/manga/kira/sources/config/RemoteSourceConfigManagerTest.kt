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

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
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
    fun cache_overrides_bundled_for_same_api() = runTest {
        val store = FakeConfigStore(
            bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))),
            cached = configJson(3, listOf(SourceJson("a", label = "cache-a"))),
        )
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

        val effective = manager.refresh().valueOrFail()
        assertEquals("cache-a", effective.label("a"))
        assertEquals(3, effective.revision)
        assertEquals(UpdateState.Origin.CACHE, (manager.state.value as UpdateState.Active).source)
    }

    @Test
    fun remote_is_not_fetched_when_disabled() = runTest {
        // No RemoteConfigSource wired -> Stage-0 default -> only bundled+cache.
        val store = FakeConfigStore(bundled = configJson(2, listOf(SourceJson("a", label = "bundled-a"))))
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

        val effective = manager.refresh().valueOrFail()
        assertEquals("bundled-a", effective.label("a"))
        assertEquals(0, store.writeCount)
    }

    @Test
    fun remote_with_bad_signature_is_ignored_and_not_cached() = runTest {
        val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))))
        val remote = RemoteConfigSource { RemoteConfigPayload(configJson(9, listOf(SourceJson("a", label = "remote-a"))), "sig") }
        val manager = RemoteSourceConfigManager(store, FakeVerifier(result = false), SchemaOnlyValidator(), remote)

        val effective = manager.refresh().valueOrFail()
        assertEquals("bundled-a", effective.label("a")) // remote rejected
        assertEquals(0, store.writeCount) // nothing cached
    }

    @Test
    fun remote_with_good_signature_wins_and_is_cached() = runTest {
        val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))))
        val remoteRaw = configJson(9, listOf(SourceJson("a", label = "remote-a")))
        val manager = RemoteSourceConfigManager(store, FakeVerifier(result = true), SchemaOnlyValidator(), RemoteConfigSource { RemoteConfigPayload(remoteRaw, "sig") })

        val effective = manager.refresh().valueOrFail()
        assertEquals("remote-a", effective.label("a"))
        assertEquals(9, effective.revision)
        assertEquals(1, store.writeCount)
        assertEquals(remoteRaw, store.lastWritten) // cache only what we verified
        assertEquals(UpdateState.Origin.REMOTE, (manager.state.value as UpdateState.Active).source)
    }

    @Test
    fun corrupt_cache_is_dropped_and_bundled_stays() = runTest {
        val store = FakeConfigStore(
            bundled = configJson(1, listOf(SourceJson("a", label = "bundled-a"))),
            cached = "{ this is not valid json",
        )
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

        val effective = manager.refresh().valueOrFail()
        assertEquals("bundled-a", effective.label("a"))
    }

    @Test
    fun bundled_priority_pin_resists_remote_override() = runTest {
        val store = FakeConfigStore(bundled = configJson(1, listOf(SourceJson("a", priority = 100, label = "bundled-pinned"))))
        val remoteRaw = configJson(9, listOf(SourceJson("a", priority = 0, label = "remote-a")))
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator(), RemoteConfigSource { RemoteConfigPayload(remoteRaw, "sig") })

        val effective = manager.refresh().valueOrFail()
        assertEquals("bundled-pinned", effective.label("a")) // higher-priority bundled source wins
    }

    @Test
    fun missing_bundled_falls_back_to_empty_then_cache_supplies_sources() = runTest {
        val store = FakeConfigStore(bundled = null, cached = configJson(2, listOf(SourceJson("a", label = "cache-a"))))
        val manager = RemoteSourceConfigManager(store, FakeVerifier(true), SchemaOnlyValidator())

        // Before refresh: empty floor (no bundled asset present).
        assertTrue(manager.activeDocument().sources.isEmpty())
        // After refresh: cache supplies the sources.
        val effective = manager.refresh().valueOrFail()
        assertEquals("cache-a", effective.label("a"))
    }
}
