package me.manga.kira.core.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebViewUrlSandboxTest {
    private val sandbox = WebViewUrlSandbox("https://reader.example.com/chapter/1")

    @Test
    fun mainFrameAllowsOnlyPinnedNetworkHostAndBrowserBlankPage() {
        assertTrue(sandbox.isAllowed("https://reader.example.com/chapter/2", isMainFrame = true))
        assertTrue(sandbox.isAllowed("https://cdn.reader.example.com/chapter/2", isMainFrame = true))
        assertTrue(sandbox.isAllowed("about:blank", isMainFrame = true))

        assertFalse(sandbox.isAllowed("about:about", isMainFrame = true))
        assertFalse(sandbox.isAllowed("data:text/html,blank", isMainFrame = true))
        assertFalse(sandbox.isAllowed("https://example.com.evil.test", isMainFrame = true))
        assertFalse(sandbox.isAllowed("not a url", isMainFrame = true))
    }

    @Test
    fun subFrameKeepsInlineContentButBlocksExecutableLocalSchemes() {
        assertTrue(sandbox.isAllowed("about:about", isMainFrame = false))
        assertTrue(sandbox.isAllowed("data:text/html,inline", isMainFrame = false))

        assertFalse(sandbox.isAllowed("javascript:alert(1)", isMainFrame = false))
        assertFalse(sandbox.isAllowed("file:///private/example", isMainFrame = false))
    }
}
