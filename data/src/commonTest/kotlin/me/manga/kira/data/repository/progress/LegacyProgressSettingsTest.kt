package me.manga.kira.data.repository.progress

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.progress.LegacyProgressCleanup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyProgressSettingsTest {
    @Test
    fun exact_capture_matches_old_codec_with_literal_separator_and_page_zero() = runTest {
        val f = ProgressSettingsFixture()
        val url = "https://current.test/chapter/one|literal"
        // Historical on-disk fixture, deliberately independent of the production key helper.
        f.settings.putString("reader.last_page." + url.hashCode().toUInt().toString(36), "$url|0")
        val captured = assertNotNull(f.store.capture(url))
        assertEquals("$url|0", captured.payload)
        assertEquals(legacyProgressKey(url), captured.key)
        assertEquals(LegacyProgressPosition(url, 0), captured.decode(url))
    }

    @Test
    fun hash_collision_and_malformed_negative_or_overflow_payloads_never_decode_as_requested_owner() = runTest {
        val f = ProgressSettingsFixture()
        val first = "https://current.test/chapter/Aa"
        val collision = "https://current.test/chapter/BB"
        assertEquals(legacyProgressKey(first), legacyProgressKey(collision))
        f.settings.putString(legacyProgressKey(first), "$collision|6")
        assertNull(assertNotNull(f.store.capture(first)).decode(first))
        for (payload in listOf("$first|", "$first|-1", "$first|2147483648", "no separator")) {
            assertNull(CapturedLegacyProgress(legacyProgressKey(first), payload).decode(first))
        }
        assertNull(CapturedLegacyProgress("wrong-key", "$first|6").decode(first))
    }

    @Test
    fun taking_shared_gate_does_not_grant_exclusive_legacy_writer_ownership() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed()
        assertEquals(LegacyProgressCleanup.OWNERSHIP_UNAVAILABLE, f.store.cleanup(captured))
        assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
        assertEquals(1, f.ownership.attempts)
        assertEquals(0, f.settings.removeAttempts)
    }

    @Test
    fun lease_is_held_through_exact_compare_and_remove() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed()
        f.ownership.granted = true
        f.settings.beforeRemove = { assertTrue(f.ownership.held) }
        assertEquals(LegacyProgressCleanup.ACKNOWLEDGED, f.store.cleanup(captured))
        assertNull(f.settings.getStringOrNull(captured.key))
        assertFalse(f.ownership.held)
        assertEquals(1, f.settings.removeAttempts)
    }

    @Test
    fun change_while_obtaining_lease_is_reread_and_full_payload_spelling_is_preserved() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed(pageText = "07")
        f.ownership.granted = true
        f.ownership.beforeGrant = { f.settings.putString(captured.key, "$SETTINGS_CHAPTER|7") }
        assertEquals(LegacyProgressCleanup.CONFLICT, f.store.cleanup(captured))
        assertEquals("$SETTINGS_CHAPTER|7", f.settings.getStringOrNull(captured.key))
        assertEquals(0, f.settings.removeAttempts)
    }

    @Test
    fun shared_gate_serializes_capture_with_other_explicit_legacy_accessors() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed()
        val release = CompletableDeferred<Unit>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            f.gate.withAccess {
                release.await()
                f.settings.putString(captured.key, "$SETTINGS_CHAPTER|9")
            }
        }
        val reading = async(start = CoroutineStart.UNDISPATCHED) { f.store.capture(SETTINGS_CHAPTER) }
        assertFalse(reading.isCompleted)
        release.complete(Unit)
        assertEquals("$SETTINGS_CHAPTER|9", reading.await()?.payload)
        writer.join()
    }

    @Test
    fun absence_or_mismatch_can_be_observed_without_destructive_ownership() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed()
        f.settings.putString(captured.key, "$SETTINGS_CHAPTER|8")
        assertEquals(LegacyProgressCleanup.CONFLICT, f.store.cleanup(captured))
        f.settings.remove(captured.key)
        assertEquals(LegacyProgressCleanup.ACKNOWLEDGED, f.store.cleanup(captured))
        assertEquals(0, f.ownership.attempts)
    }

    @Test
    fun throwing_remove_releases_ownership_without_reporting_acknowledgement() = runTest {
        val f = ProgressSettingsFixture()
        val captured = f.seed()
        f.ownership.granted = true
        f.settings.beforeRemove = { error("Controlled Settings removal failure") }
        assertFailsWith<IllegalStateException> { f.store.cleanup(captured) }
        assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
        assertFalse(f.ownership.held)
    }
}

/** ObservableSettings fault collaborator, not a platform persistence/flush simulation. */
internal class ProgressTestSettings(private val backing: ObservableSettings = MapSettings()) :
    ObservableSettings by backing {
    var beforeRemove: (String) -> Unit = {}
    var removeAttempts = 0
        private set

    override fun remove(key: String) {
        removeAttempts++
        beforeRemove(key)
        backing.remove(key)
    }
}

/** Tests must grant ownership explicitly. Shipping composition cannot use this collaborator. */
internal class ProgressTestWriterOwnership : LegacyProgressWriterOwnership {
    var granted = false
    var held = false
        private set
    var attempts = 0
        private set
    var beforeGrant: suspend () -> Unit = {}

    override suspend fun <T : Any> withExclusiveWriterOwnership(block: () -> T): T? {
        attempts++
        beforeGrant()
        if (!granted) return null
        check(!held)
        held = true
        return try { block() } finally { held = false }
    }
}

private class ProgressSettingsFixture {
    val settings = ProgressTestSettings()
    val gate = LegacyProgressSettingsGate()
    val ownership = ProgressTestWriterOwnership()
    val store = LegacyProgressSettings(settings, gate, ownership)

    fun seed(pageText: String = "6"): CapturedLegacyProgress {
        val captured = CapturedLegacyProgress(legacyProgressKey(SETTINGS_CHAPTER), "$SETTINGS_CHAPTER|$pageText")
        settings.putString(captured.key, captured.payload)
        return captured
    }
}

private const val SETTINGS_CHAPTER = "https://current.test/chapter/one"
