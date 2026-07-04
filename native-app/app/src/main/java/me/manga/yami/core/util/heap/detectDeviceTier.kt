package me.manga.yamiapk.core.util.heap

import android.app.ActivityManager
import android.content.Context

enum class DeviceTier { LOW_END, MID_RANGE, HIGH_END }

fun detectDeviceTier(context: Context): DeviceTier {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val heap = am.largeMemoryClass // because largeHeap=true

    return when {
        heap < 300 -> DeviceTier.LOW_END       // 256 MB or less
        heap < 600 -> DeviceTier.MID_RANGE     // 384–512 MB
        else -> DeviceTier.HIGH_END            // 768 MB or more
    }
}