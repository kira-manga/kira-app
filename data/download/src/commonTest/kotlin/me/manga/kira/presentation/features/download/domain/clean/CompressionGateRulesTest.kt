package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the iOS compression-admission matrix. These are pure rules so thermal recovery, Low
 * Power Mode opt-in, foreground settling, and OS-granted background windows can be verified
 * without starting URLSession or touching the filesystem.
 */
class CompressionGateRulesTest {

    @Test
    fun foreground_requiresSettledHealthyDevice() {
        assertTrue(
            CompressionGateRules.canCompress(
                appActive = true,
                appSettled = true,
                thermallyStressed = false,
                lowPowerMode = false,
                allowLowPowerCompression = false,
                backgroundWindowActive = false,
            ),
        )
        assertFalse(
            CompressionGateRules.canCompress(
                appActive = true,
                appSettled = false,
                thermallyStressed = false,
                lowPowerMode = false,
                allowLowPowerCompression = false,
                backgroundWindowActive = false,
            ),
        )
    }

    @Test
    fun thermalStressAlwaysBlocksForeground_evenWhenLowPowerOptedIn() {
        assertTrue(CompressionGateRules.isDeferred(true, lowPowerMode = false, allowLowPowerCompression = true))
        assertFalse(CompressionGateRules.isLowPowerDeferred(true, lowPowerMode = true, allowLowPowerCompression = false))
        assertFalse(
            CompressionGateRules.canCompress(
                appActive = true,
                appSettled = true,
                thermallyStressed = true,
                lowPowerMode = true,
                allowLowPowerCompression = true,
                backgroundWindowActive = false,
            ),
        )
    }

    @Test
    fun lowPowerMode_blocksByDefault_andAllowsExplicitOptIn() {
        assertTrue(CompressionGateRules.isLowPowerDeferred(false, lowPowerMode = true, allowLowPowerCompression = false))
        assertFalse(
            CompressionGateRules.canCompress(
                appActive = true,
                appSettled = true,
                thermallyStressed = false,
                lowPowerMode = true,
                allowLowPowerCompression = false,
                backgroundWindowActive = false,
            ),
        )
        assertTrue(
            CompressionGateRules.canCompress(
                appActive = true,
                appSettled = true,
                thermallyStressed = false,
                lowPowerMode = true,
                allowLowPowerCompression = true,
                backgroundWindowActive = false,
            ),
        )
    }

    @Test
    fun backgroundCompression_requiresOsWindow_butDoesNotUseForegroundDeferral() {
        assertTrue(
            CompressionGateRules.canCompress(
                appActive = false,
                appSettled = false,
                thermallyStressed = true,
                lowPowerMode = true,
                allowLowPowerCompression = false,
                backgroundWindowActive = true,
            ),
        )
        assertFalse(
            CompressionGateRules.canCompress(
                appActive = false,
                appSettled = true,
                thermallyStressed = false,
                lowPowerMode = false,
                allowLowPowerCompression = true,
                backgroundWindowActive = false,
            ),
        )
    }
}
