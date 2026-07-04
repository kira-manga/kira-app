package me.manga.kira.platform.storage

import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.test.runTest
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #12 — Desktop per-source headers must not crash when the aggregate would exceed the
 * java.util.prefs 8 KB value cap. Runs against a REAL [PreferencesSettings] (MapSettings has no
 * cap and would mask the bug). The fix stores one bounded value per API under a hashed key, with a
 * read-both-formats fallback to the legacy aggregate blob.
 */
class DataStoreHeadersOverflowTest {

    private val node: Preferences = Preferences.userRoot().node("me.manga.kira.test.headers")
    private lateinit var helper: DataStoreHelper

    @BeforeTest
    fun setUp() {
        node.clear()
        helper = DataStoreHelper(PreferencesSettings(node))
    }

    @AfterTest
    fun tearDown() {
        node.clear()
    }

    @Test
    fun manySourcesWithLargeHeaders_doNotExceedPerValueCap() = runTest {
        // 50 sources × a ~300-char cookie → the legacy single-blob aggregate would be > 8 KB and
        // throw on the Desktop Preferences backend. Per-API keys keep each value small.
        val bigCookie = "cf_clearance=" + "a".repeat(300)
        val apis = (1..50).map { "source_$it" }
        apis.forEach { api ->
            helper.saveHeadersForApi(api, mapOf("Cookie" to bigCookie, "User-Agent" to "Yami/1.0"))
        }
        apis.forEach { api ->
            assertEquals(
                mapOf("Cookie" to bigCookie, "User-Agent" to "Yami/1.0"),
                helper.getHeadersForApi(api),
                "every source's headers round-trip under the per-API-key layout ($api)",
            )
        }
    }

    @Test
    fun unknownApi_returnsNull() = runTest {
        helper.saveHeadersForApi("known", mapOf("Cookie" to "x"))
        assertNull(helper.getHeadersForApi("never-saved"))
    }

    @Test
    fun legacyAggregateBlob_isReadViaFallback() = runTest {
        // Pre-seed the legacy single-blob format directly (the pre-#12 wire format) and assert a
        // read falls back to it when no per-API key exists.
        node.put(StorageKeys.HEADERS_MAP_JSON, """{"legacyApi":{"Cookie":"cf=legacy","X":"1"}}""")
        // Rebuild the helper so it sees the pre-seeded value via a fresh settings read.
        helper = DataStoreHelper(PreferencesSettings(node))

        assertEquals(
            mapOf("Cookie" to "cf=legacy", "X" to "1"),
            helper.getHeadersForApi("legacyApi"),
            "headers saved before #12 (legacy aggregate blob) keep working via the read-both fallback",
        )
    }
}
