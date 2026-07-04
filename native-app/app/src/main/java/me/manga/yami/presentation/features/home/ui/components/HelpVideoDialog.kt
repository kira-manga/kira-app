package me.manga.yamiapk.presentation.features.home.ui.components

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.manga.yamiapk.R
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun HelpVideoDialog(
    onDismiss: () -> Unit
) {
    var isVideoLoading by remember { mutableStateOf(true) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var cleanupJob by remember { mutableStateOf<Job?>(null) }
    val isCleaningUp = remember { AtomicBoolean(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Timeout for video loading to prevent indefinite loading
    LaunchedEffect(Unit) {
        delay(15000) // 15 second timeout
        if (isVideoLoading) {
            isVideoLoading = false
            // Force cleanup if still loading after timeout
            videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
        }
    }

    // Handle lifecycle events
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    videoView?.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!isVideoLoading && !isCleaningUp.get()) {
                        videoView?.resume()
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cleanupJob?.cancel()
            videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
        }
    }

    Dialog(
        onDismissRequest = {
            // Non-blocking dismiss - cleanup happens asynchronously
            videoView?.let {
                cleanupJob = safelyReleaseVideoAsync(it, isCleaningUp)
            }
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Box {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                videoView = this

                                // Configure for better network handling
                                val controller = MediaController(ctx).also {
                                    it.setAnchorView(this)
                                }
                                setMediaController(controller)

                                setOnPreparedListener { mediaPlayer ->
                                    if (!isCleaningUp.get()) {
                                        isVideoLoading = false
                                        try {
                                            mediaPlayer.setVideoScalingMode(
                                                android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                            )
                                            start()
                                        } catch (e: Exception) {
                                            android.util.Log.e("VideoDialog", "Error starting video", e)
                                        }
                                    }
                                }

                                setOnErrorListener { _, what, extra ->
                                    isVideoLoading = false
                                    android.util.Log.e("VideoDialog", "Video error: what=$what, extra=$extra")
                                    true // Handle error to prevent system dialog
                                }

                                setOnInfoListener { _, what, extra ->
                                    android.util.Log.d("VideoDialog", "Video info: what=$what, extra=$extra")
                                    false
                                }

                                // Set video URI with error handling
                                try {
                                    val uri = "https://yamimanga.me/video/help_video.mp4".toUri()
                                    setVideoURI(uri)
                                } catch (e: Exception) {
                                    isVideoLoading = false
                                    android.util.Log.e("VideoDialog", "Failed to set video URI", e)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            videoView = view
                        }
                    )

                    // Loading indicator
                    if (isVideoLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            // Non-blocking close - cleanup happens asynchronously
                            videoView?.let {
                                cleanupJob = safelyReleaseVideoAsync(it, isCleaningUp)
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_description_close),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Performs video cleanup asynchronously to prevent ANRs.
 * This is the KEY improvement - all cleanup operations happen off the main thread.
 */
private fun safelyReleaseVideoAsync(videoView: VideoView, isCleaningUp: AtomicBoolean): Job {
    return CoroutineScope(Dispatchers.Main).launch {
        if (isCleaningUp.getAndSet(true)) {
            return@launch
        }

        try {
            // Step 1: Immediate UI feedback - clear the view first
            try {
                videoView.setMediaController(null)
            } catch (e: Exception) {
                android.util.Log.e("VideoDialog", "Error clearing controller", e)
            }

            // Step 2: Move ALL video operations to background with shorter timeout
            withContext(Dispatchers.Default) { // Use Default instead of IO for CPU-bound cleanup
                withTimeoutOrNull(1000L) { // Shorter timeout - 1 second max
                    try {
                        // Post these operations to avoid blocking
                        withContext(Dispatchers.Main.immediate) {
                            try {
                                if (videoView.isPlaying) {
                                    videoView.pause() // Less aggressive than stopPlayback
                                }
                                videoView.suspend()
                            } catch (e: Exception) {
                                // Log but don't rethrow
                                android.util.Log.e("VideoDialog", "Cleanup error (non-fatal)", e)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VideoDialog", "Background cleanup failed", e)
                    }
                }
            }
        } finally {
            isCleaningUp.set(false)
        }
    }
}

/**
 * Alternative cleanup method that's even more aggressive about preventing ANRs.
 * Use this if you still experience ANRs with the above method.
 */
private fun emergencyVideoCleanup(videoView: VideoView) {
    CoroutineScope(Dispatchers.IO).launch {
        withTimeoutOrNull(1000L) { // 1 second max
            try {
                // Just try to stop - don't wait for responses
                withContext(Dispatchers.Main) {
                    try {
                        videoView.stopPlayback()
                        videoView.setMediaController(null)
                    } catch (ignored: Exception) {
                        // Ignore all exceptions - we just want to attempt cleanup
                    }
                }

                // Give a small delay for cleanup to process
                delay(100)

                withContext(Dispatchers.Main) {
                    try {
                        videoView.suspend()
                    } catch (ignored: Exception) {
                        // Ignore - at this point we've done our best
                    }
                }
            } catch (ignored: Exception) {
                // Emergency cleanup - ignore all errors
            }
        }
    }
}