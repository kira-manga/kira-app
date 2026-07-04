package me.manga.yamiapk.ad_mob.native_ads.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.ad_mob.native_ads.NativeAdQueue

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NativeAdQueueEntryPoint {
    fun nativeAdQueue(): NativeAdQueue
}