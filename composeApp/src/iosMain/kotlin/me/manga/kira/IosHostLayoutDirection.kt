package me.manga.kira

import platform.UIKit.UIView
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft

/**
 * Keeps UIKit's host direction aligned with Compose's live app-language direction.
 *
 * Compose's default iOS back recognizer derives its start edge from the root UIView, while the
 * default NavHost derives its accepted edge from LocalLayoutDirection. Those values must agree.
 */
internal object IosHostLayoutDirection {
    private var hostView: UIView? = null
    private var requestedRtl: Boolean? = null

    fun bind(view: UIView) {
        hostView = view
        requestedRtl?.let { apply(view, it) }
    }

    fun synchronize(isRtl: Boolean) {
        requestedRtl = isRtl
        hostView?.let { apply(it, isRtl) }
    }

    private fun apply(view: UIView, isRtl: Boolean) {
        val desired =
            if (isRtl) {
                UISemanticContentAttributeForceRightToLeft
            } else {
                UISemanticContentAttributeForceLeftToRight
            }
        if (view.semanticContentAttribute == desired) return

        view.semanticContentAttribute = desired

        if (view.window != null) {
            // Compose UI 1.11.x samples effectiveUserInterfaceLayoutDirection from its root view
            // inside didMoveToWindow. Re-running that idempotent callback refreshes the built-in
            // edge recognizer after an in-process language switch without recreating the
            // UIViewController, Compose tree, NavController, or back stack.
            view.didMoveToWindow()
        }
    }
}
