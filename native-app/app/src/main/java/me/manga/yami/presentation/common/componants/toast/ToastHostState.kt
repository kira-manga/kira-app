package me.manga.yamiapk.presentation.common.componants.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Holds the current toast message. Update `show(message)` to trigger.
 */
class ToastHostState {
    private val _message = mutableStateOf<String?>(null)
    val message: State<String?> = _message

    /** Show a new toast (auto‑dismiss in [durationMillis]). */
    @Composable
    fun show(message: String, durationMillis: Long = 2_000L) {
        _message.value = message
        // clear after duration
        LaunchedEffect(message) {
            delay(durationMillis)
            _message.value = null
        }
    }

    fun show(message: String) {
        _message.value = message
    }
    internal fun clear() {
        _message.value = null
    }
}

/**
 * Place this at the top level of your screen (inside your root Box/Scaffold).
 */
@Composable
fun ToastHost(state: ToastHostState, durationMillis: Long = 2_000L) {
    state.message.value?.let { msg ->
        // 1) Animate in/out
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it } + fadeIn(),
            exit  = slideOutVertically { it } + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // 2) Auto-dismiss after [durationMillis]
        LaunchedEffect(msg) {
            delay(durationMillis)
            state.clear()
        }
    }
}

