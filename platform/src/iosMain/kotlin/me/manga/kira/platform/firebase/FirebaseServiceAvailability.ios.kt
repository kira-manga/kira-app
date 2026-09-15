package me.manga.kira.platform.firebase

import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** Debug frameworks and non-Store hosts cannot resolve production Firebase-backed services. */
@OptIn(ExperimentalNativeApi::class)
fun firebaseServicesAvailable(): Boolean =
    !Platform.isDebugBinary && NSBundle.mainBundle.bundleIdentifier == "me.manga.kira" &&
        NSBundle.mainBundle.objectForInfoDictionaryKey("KiraFirebaseServicesEnabled")
            ?.toString()?.lowercase() in setOf("yes", "true", "1")
