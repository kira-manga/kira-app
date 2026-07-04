package me.manga.yamiapk.presentation.features.whatsnew.ui.components

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import me.manga.yamiapk.R
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun SafeVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = true, // Add this parameter
    onFullscreenClick: (() -> Unit)? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    val isCleaningUp = remember { AtomicBoolean(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Stop and hide video immediately when becoming inactive
    LaunchedEffect(isActive) {
        if (!isActive) {
            try {
                videoView?.stopPlayback()
                isPlaying = false
            } catch (e: Exception) {
                android.util.Log.e("SafeVideoPlayer", "Error stopping video on inactive", e)
            }
        }
    }

    LaunchedEffect(videoUrl) {
        delay(10000)
        if (isLoading && !hasError) {
            hasError = true
            isLoading = false
            videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!isCleaningUp.get()) {
                        try {
                            videoView?.pause()
                            isPlaying = false
                        } catch (e: Exception) {
                            android.util.Log.e("SafeVideoPlayer", "Error pausing video", e)
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!isLoading && !hasError && !isCleaningUp.get() && isActive) {
                        try {
                            videoView?.resume()
                        } catch (e: Exception) {
                            android.util.Log.e("SafeVideoPlayer", "Error resuming video", e)
                        }
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
            try {
                videoView?.stopPlayback()
            } catch (e: Exception) {
                android.util.Log.e("SafeVideoPlayer", "Error stopping video", e)
            }
            videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
        }
    }

    // Only show the video when active
    if (isActive) {
        Box(modifier = modifier) {
            if (hasError) {
                VideoErrorPlaceholder(
                    onRetry = {
                        hasError = false
                        isLoading = true
                        videoView?.let { safelyReleaseVideoAsync(it, isCleaningUp) }
                    }
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            setOnPreparedListener { mediaPlayer ->
                                if (!isCleaningUp.get()) {
                                    try {
                                        isLoading = false
                                        hasError = false
                                        mediaPlayer.isLooping = true
                                        mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                                    } catch (e: Exception) {
                                        android.util.Log.e("SafeVideoPlayer", "Error in onPrepared", e)
                                        hasError = true
                                        isLoading = false
                                    }
                                }
                            }

                            setOnCompletionListener {
                                if (!isCleaningUp.get()) {
                                    isPlaying = false
                                }
                            }

                            setOnErrorListener { _, what, extra ->
                                android.util.Log.e("SafeVideoPlayer", "Video error: what=$what, extra=$extra")
                                if (!isCleaningUp.get()) {
                                    hasError = true
                                    isLoading = false
                                    isPlaying = false
                                }
                                true
                            }

                            try {
                                setVideoURI(videoUrl.toUri())
                            } catch (e: Exception) {
                                android.util.Log.e("SafeVideoPlayer", "Failed to set video URI", e)
                                hasError = true
                                isLoading = false
                            }

                            videoView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Loading video...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isLoading && !hasError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                if (!isCleaningUp.get()) {
                                    videoView?.let { vv ->
                                        try {
                                            if (isPlaying) {
                                                vv.pause()
                                                isPlaying = false
                                            } else {
                                                vv.start()
                                                isPlaying = true
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("SafeVideoPlayer", "Error controlling playback", e)
                                            hasError = true
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedVisibility(
                            visible = !isPlaying,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(200))
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = CircleShape,
                                modifier = Modifier.size(64.dp),
                                shadowElevation = 4.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.play),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (onFullscreenClick != null && !isLoading && !hasError) {
                    IconButton(
                        onClick = onFullscreenClick,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                CircleShape
                            )
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.fullscreen),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Show placeholder when inactive to prevent black box
        VideoPlaceholder()
    }
}
@Composable
fun VideoErrorPlaceholder(onRetry: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Error",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Failed to load video",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun VideoPlaceholder(mediaSize: Dp = 220.dp) {
    Box(
        modifier = Modifier
            .width(mediaSize)
            .height(mediaSize * 0.75f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.play_video),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.video_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun safelyReleaseVideoAsync(videoView: VideoView, isCleaningUp: AtomicBoolean): Job {
    return CoroutineScope(Dispatchers.Main).launch {
        if (isCleaningUp.getAndSet(true)) {
            return@launch
        }

        try {
            try {
                videoView.setMediaController(null)
            } catch (e: Exception) {
                android.util.Log.e("SafeVideoPlayer", "Error clearing controller", e)
            }

            withContext(Dispatchers.Default) {
                withTimeoutOrNull(1000L) {
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            try {
                                if (videoView.isPlaying) {
                                    videoView.pause()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("SafeVideoPlayer", "Error pausing", e)
                            }
                        }

                        delay(100)

                        withContext(Dispatchers.Main.immediate) {
                            try {
                                videoView.suspend()
                            } catch (e: Exception) {
                                android.util.Log.e("SafeVideoPlayer", "Error suspending", e)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SafeVideoPlayer", "Error during cleanup", e)
                    }
                }
            }
        } finally {
            isCleaningUp.set(false)
        }
    }
}