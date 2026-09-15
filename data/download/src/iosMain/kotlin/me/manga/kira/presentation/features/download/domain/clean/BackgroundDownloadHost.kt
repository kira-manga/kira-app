package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.coroutines.CoroutineScope
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.notification.DownloadNotifier

/**
 * Host-lifetime integration for the background queue: execution ownership, CPU-window requests,
 * the host's work snapshot, and download notifications. Holds the same injected instances; the
 * engine still owns observer registration, gate collection, scheduling edges, and notification order.
 */
class BackgroundDownloadHost(
    val applicationScope: CoroutineScope,
    val scheduler: BackgroundScheduler,
    val workSignal: BackgroundWorkSignal,
    val downloadNotifier: DownloadNotifier,
)
