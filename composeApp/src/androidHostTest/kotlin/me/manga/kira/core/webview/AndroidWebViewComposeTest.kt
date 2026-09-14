package me.manga.kira.core.webview

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

// Explicitly owned, test-only activity: no ui-test-manifest dependency in shipping androidMain.
class WebViewTestActivity : ComponentActivity()

abstract class AndroidWebViewComposeTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var activityController: ActivityController<WebViewTestActivity>? = null
    protected val activity: WebViewTestActivity get() = requireNotNull(activityController).get()

    @Before
    fun createHost() {
        compose.runOnUiThread {
            activityController = Robolectric.buildActivity(WebViewTestActivity::class.java).setup().visible()
        }
    }

    @After
    fun destroyHost() {
        compose.runOnUiThread {
            activityController?.pause()?.stop()?.destroy()
            activityController = null
        }
    }

    protected fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { activity.setContent { KiraTheme(darkTheme = false, content = content) } }
        compose.waitForIdle()
    }

    protected fun pauseHost() {
        compose.runOnUiThread { requireNotNull(activityController).pause() }
        compose.waitForIdle()
    }

    protected fun resumeHost() {
        compose.runOnUiThread { requireNotNull(activityController).resume() }
        compose.waitForIdle()
    }

    protected fun label(resource: StringResource): String = runBlocking { getString(resource) }
}
