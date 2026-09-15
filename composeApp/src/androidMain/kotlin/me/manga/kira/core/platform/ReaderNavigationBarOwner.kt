package me.manga.kira.core.platform

import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * One idempotent Reader lease. Composition disposal and entry destruction can both release it.
 * All operations are main-thread confined, including Android window and lifecycle callbacks.
 *
 * The registry has weak Window keys and integer values, never a controller/owner back-reference.
 * Callback registrations retain this lease, but it only weakly references their Window, lifecycle
 * and observer. Final release removes registrations; an abandoned Window cannot be rooted here.
 */
internal class ReaderNavigationBarOwner(
    window: Window,
    lifecycle: Lifecycle,
) : LifecycleEventObserver,
    View.OnAttachStateChangeListener,
    ViewTreeObserver.OnWindowFocusChangeListener {
    private val windowReference = WeakReference(window)
    private val lifecycleReference = WeakReference(lifecycle)
    private var focusObserver: WeakReference<ViewTreeObserver>? = null
    private var released = false

    init {
        checkMainThread()
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            released = true
            windowReference.clear()
            lifecycleReference.clear()
        } else {
            owners[window] = (owners[window] ?: 0) + 1
            window.decorView.addOnAttachStateChangeListener(this)
            observeWindowFocus(window.decorView)
            lifecycle.addObserver(this)
            reapply()
        }
    }

    fun release() {
        checkMainThread()
        if (released) return
        released = true
        lifecycleReference.get()?.removeObserver(this)
        lifecycleReference.clear()
        stopObservingWindowFocus()
        val window = windowReference.get()
        windowReference.clear()
        if (window != null) {
            window.decorView.removeOnAttachStateChangeListener(this)
            releaseWindow(window)
        }
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> reapply()
            Lifecycle.Event.ON_DESTROY -> release()
            else -> Unit
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) reapply()
    }

    override fun onViewAttachedToWindow(view: View) {
        if (released) return
        observeWindowFocus(view)
        reapply()
    }

    override fun onViewDetachedFromWindow(view: View) {
        stopObservingWindowFocus()
    }

    private fun reapply() {
        checkMainThread()
        if (released) return
        val window = windowReference.get() ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }

    private fun observeWindowFocus(view: View) {
        stopObservingWindowFocus()
        if (!view.isAttachedToWindow) return
        val observer = view.viewTreeObserver
        // A ViewTreeObserver can be replaced/merged on attachment. Do not duplicate our callback.
        observer.removeOnWindowFocusChangeListener(this)
        observer.addOnWindowFocusChangeListener(this)
        focusObserver = WeakReference(observer)
    }

    private fun stopObservingWindowFocus() {
        focusObserver?.get()?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(this)
        focusObserver = null
    }

    private companion object {
        val owners = WeakHashMap<Window, Int>()

        fun releaseWindow(window: Window) {
            val remaining = requireNotNull(owners[window]) - 1
            if (remaining == 0) {
                owners.remove(window)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                owners[window] = remaining
            }
        }

        fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Reader navigation-bar ownership must stay on the main thread"
            }
        }
    }
}
