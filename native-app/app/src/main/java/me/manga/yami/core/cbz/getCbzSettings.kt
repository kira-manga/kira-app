package me.manga.yamiapk.core.cbz

import me.manga.yamiapk.core.util.heap.DeviceTier

fun getCbzSettings(tier: DeviceTier): CbzSettings {

    return when (tier) {
        DeviceTier.LOW_END -> CbzSettings(
            maxParallelDecode = 1,
            maxParallelCompress = 2,
            regionDecodeThreshold = 6000,
            samplingThreshold = 20_000_000L, // 20MB
            webpQuality = 70
        )
        DeviceTier.MID_RANGE -> CbzSettings(
            maxParallelDecode = 2,
            maxParallelCompress = 4,
            regionDecodeThreshold = 9000,
            samplingThreshold = 40_000_000L, // 40MB
            webpQuality = 75
        )
        DeviceTier.HIGH_END -> CbzSettings(
            maxParallelDecode = 3, // We allow 2 parallel decodes only on very strong devices
            maxParallelCompress = 6,
            regionDecodeThreshold = 12000,
            samplingThreshold = 70_000_000L, // 70MB
            webpQuality = 85
        )
    }
}
