package me.manga.kira.reader

import me.manga.kira.presentation.reader.ReaderEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderNativeEffectDispatchTest {
    @Test
    fun openInWebViewEffectInvokesHostCallbackExactlyOnce() {
        var calls = 0
        var receivedUrl = ""
        var receivedApi = ""

        val handled =
            dispatchOpenInWebViewEffect(
                ReaderEffect.OpenChapterInWebView(
                    url = "https://reader.example/chapter/1",
                    api = "Example",
                ),
            ) { url, api ->
                calls += 1
                receivedUrl = url
                receivedApi = api
            }

        assertTrue(handled)
        assertEquals(1, calls)
        assertEquals("https://reader.example/chapter/1", receivedUrl)
        assertEquals("Example", receivedApi)
    }

    @Test
    fun unrelatedEffectDoesNotInvokeWebViewCallback() {
        var calls = 0

        val handled =
            dispatchOpenInWebViewEffect(ReaderEffect.NavigateBack) { _, _ ->
                calls += 1
            }

        assertFalse(handled)
        assertEquals(0, calls)
    }
}
