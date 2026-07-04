package me.manga.kira.presentation.features.download

/**
 * Compile-time rollback switch for the iOS background-download engine (background-downloads M2+).
 *
 * When [IOS_BACKGROUND_ENGINE_ENABLED] is `false` (default) iOS binds the proven
 * `CoroutineDownloadRepositoryImpl` (the in-process coroutine queue shared with Desktop). When `true`
 * iOS binds `BackgroundUrlSessionDownloadRepository`, which hands page transfers to a background
 * `NSURLSession` so they keep running while the app is suspended.
 *
 * **Currently `true` for the on-device test build** (background-downloads M6). Set it back to `false`
 * to roll back instantly to the legacy coroutine engine — which therefore stays compiled in
 * `nonAndroidMain`. Android (WorkManager) and Desktop ignore this flag entirely.
 *
 * While testing, the subsystem also emits verbose structured logs under the `KiraBgDownload` tag
 * ([me.manga.kira.platform.download.BgDownloadLog]); that logging is a separate switch
 * (`BgDownloadLog.VERBOSE`) so it can be silenced independently of the engine selection.
 *
 * Mirrors the `SourceDebugFlags` const-flag convention. Can later be promoted to a DataStore /
 * remote-config toggle for rebuild-free production rollback.
 */
object DownloadEngineFlags {
    const val IOS_BACKGROUND_ENGINE_ENABLED: Boolean = true
}
