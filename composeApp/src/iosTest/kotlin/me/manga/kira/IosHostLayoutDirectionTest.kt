package me.manga.kira

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.EndEdgePanGestureBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIUserInterfaceLayoutDirection.UIUserInterfaceLayoutDirectionLeftToRight
import platform.UIKit.UIUserInterfaceLayoutDirection.UIUserInterfaceLayoutDirectionRightToLeft
import platform.UIKit.UIView

class IosHostLayoutDirectionTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun navigationHost_publishesBackEventsFromBothPhysicalEdges() {
        val configuration = ComposeUIViewControllerConfiguration()

        configuration.configureKiraNavigationHost()

        assertEquals(EndEdgePanGestureBehavior.Back, configuration.endEdgePanGestureBehavior)
    }

    @Test
    fun synchronize_updatesUIKitSemanticAndEffectiveDirection() {
        val view = UIView()
        IosHostLayoutDirection.bind(view)

        IosHostLayoutDirection.synchronize(isRtl = true)
        assertEquals(UISemanticContentAttributeForceRightToLeft, view.semanticContentAttribute)
        assertEquals(UIUserInterfaceLayoutDirectionRightToLeft, view.effectiveUserInterfaceLayoutDirection)

        IosHostLayoutDirection.synchronize(isRtl = false)
        assertEquals(UISemanticContentAttributeForceLeftToRight, view.semanticContentAttribute)
        assertEquals(UIUserInterfaceLayoutDirectionLeftToRight, view.effectiveUserInterfaceLayoutDirection)
    }
}
