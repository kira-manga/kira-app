package me.manga.kira.presentation.features.download.ui.test2

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal const val GATE_TIMEOUT_SECONDS = 15L
internal const val DOWNLOAD_COMPLETION_PROGRESS = 100
internal const val FIXTURE_API = "app75-host-fixture"

internal class ImmediateFixtureForegroundUpdater(
    private val calls: AtomicInteger,
) : ForegroundUpdater {
    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: ForegroundInfo,
    ): ListenableFuture<Void> =
        CallbackToFutureAdapter.getFuture { completer ->
            calls.incrementAndGet()
            completer.set(null)
            "App75 test foreground updater (not an Android foreground service)"
        }
}
