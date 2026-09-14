package me.manga.kira.core.platform

import android.content.Context
import android.view.Window
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Same explicitly owned Activity/empty Compose rule pattern as AndroidWebViewComposeTest.
class NavigationBarTestActivity : ComponentActivity()

abstract class NavigationBarTestHost {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var controller: ActivityController<NavigationBarTestActivity>? = null
    private val siblings = mutableListOf<ActivityController<NavigationBarTestActivity>>()
    protected val activity: NavigationBarTestActivity get() = requireNotNull(controller).get()

    @Before
    fun createHost() {
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(NavigationBarTestActivity::class.java).setup().visible()
        }
    }

    @After
    fun destroyHost() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread {
            siblings.asReversed().forEach { it.close() }
            siblings.clear()
            controller?.close()
            controller = null
        }
    }

    protected fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity.setContent { Box(Modifier.fillMaxSize()) { content() } }
        }
        compose.waitForIdle()
    }

    protected fun siblingHost(): ActivityController<NavigationBarTestActivity> =
        compose.runOnUiThread {
            Robolectric
                .buildActivity(NavigationBarTestActivity::class.java)
                .setup()
                .visible()
                .also { siblings += it }
        }

    protected fun pauseHost() {
        compose.runOnUiThread { requireNotNull(controller).pause() }
        compose.waitForIdle()
    }

    protected fun resumeHost() {
        compose.runOnUiThread { requireNotNull(controller).resume() }
        compose.waitForIdle()
    }

    protected fun focusHost(focused: Boolean) {
        compose.runOnUiThread { requireNotNull(controller).windowFocusChanged(focused) }
        compose.waitForIdle()
    }

    protected fun recreateHost() {
        compose.runOnUiThread { requireNotNull(controller).recreate() }
        compose.waitForIdle()
    }
}

/** SDK35's actual InsetsController request mask, not an assertion about rendered OS pixels. */
internal class NavigationBarWindowProbe(
    window: Window,
) {
    private val controller = assertNotNull(window.insetsController)

    init {
        check(window.decorView.isAttachedToWindow)
    }

    var behavior: Int
        get() = controller.systemBarsBehavior
        set(value) {
            controller.systemBarsBehavior = value
        }

    fun showNavigation() {
        controller.show(WindowInsets.Type.navigationBars())
    }

    fun hideStatus() {
        controller.hide(WindowInsets.Type.statusBars())
    }

    fun assertNavigationVisible(visible: Boolean) {
        assertEquals(visible, isRequestedVisible(WindowInsets.Type.navigationBars()), "navigation bars")
    }

    fun assertStatusVisible(visible: Boolean) {
        assertEquals(visible, isRequestedVisible(WindowInsets.Type.statusBars()), "status bars")
    }

    private fun isRequestedVisible(type: Int): Boolean {
        val requested: Int = ReflectionHelpers.callInstanceMethod(controller, "getRequestedVisibleTypes")
        return requested and type != 0
    }
}

internal class NavigationBarEntryOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
}

internal class NavigationBarEffectFixture(
    context: Context,
    entry: LifecycleOwner,
) {
    var context by mutableStateOf(context)
    var entry by mutableStateOf(entry)
    var mounted by mutableStateOf(true)

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides entry) {
            if (mounted) HideNavigationBarSideEffect()
        }
    }
}

internal fun assertNavigationBarRegistrations(
    window: Window,
    count: Int,
) {
    val listeners = Shadows.shadowOf(window.decorView).onAttachStateChangeListeners
    assertEquals(count, listeners.filterIsInstance<ReaderNavigationBarOwner>().size)
}
