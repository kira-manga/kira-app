package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.coroutines.CoroutineScope
import me.manga.kira.platform.background.BackgroundExecutionGuard
import me.manga.kira.platform.notification.DownloadNotifier

/**
 * Host-lifetime hooks for the coroutine queue: its owning scope, background grace, and notifications.
 * iOS keeps the supplied grace/notification implementations; Desktop keeps PassThrough/NoOp. No
 * scope is created or cancelled here, and no dispatcher or notification timing is changed.
 */
class CoroutineDownloadHost(
    val applicationScope: CoroutineScope,
    val backgroundGuard: BackgroundExecutionGuard,
    val downloadNotifier: DownloadNotifier,
)
