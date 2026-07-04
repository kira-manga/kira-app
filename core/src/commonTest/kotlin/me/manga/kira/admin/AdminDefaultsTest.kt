package me.manga.kira.admin

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Pins the C1 fail-closed contract (2026-07-03): [Admin.isAdmin] must default to `false` so the
 * admin complaint console is unreachable unless a host bootstrap explicitly flips the flag in a
 * DEBUG build. A regression back to a `true` default would ship the admin surface to every
 * production user (the pre-C1 state) — this test is the tripwire. (The flag's former second
 * consumer, the `/dev/source` registry feed, was deleted in SourceRegistry retirement Phase 6.)
 */
class AdminDefaultsTest {

    @Test
    fun isAdmin_defaultsToFalse_failClosed() {
        assertFalse(
            Admin.isAdmin,
            "Admin.isAdmin must default to false — hosts opt IN for debug builds only (C1)",
        )
    }

    @Test
    fun testingMode_defaultsToFalse() {
        assertFalse(Admin.testingMode)
    }
}
