package me.manga.yamiapk.ad_mob.native_ads.di

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import me.manga.yamiapk.ad_mob.native_ads.NativeAdQueue

 fun getNativeAdQueue(context: Context): NativeAdQueue {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        NativeAdQueueEntryPoint::class.java
    )
    return entryPoint.nativeAdQueue()
}