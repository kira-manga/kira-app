package me.manga.kira.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.navigation.safePopBackStack

internal class WebViewRouteActions(
    private val navController: NavController,
    private val browser: NavBackStackEntry,
    private val controller: WebViewController,
    private val persist: (Map<String, String>?, String) -> Unit,
) {
    fun save(
        headers: Map<String, String>?,
        api: String,
    ) {
        if (controller.state.value.initialization == WebViewInitialization.Ready) persist(headers, api)
    }

    fun close(
        headers: Map<String, String>?,
        api: String,
    ) {
        if (navController.currentBackStackEntry !== browser) return
        val ready = controller.state.value.initialization == WebViewInitialization.Ready
        if (ready) save(headers, api)
        val result = navController.previousBackStackEntry?.let(::WebViewSolverReturn)
        val marker =
            if (ready) {
                result?.clearFailure(browser)
                null
            } else {
                result?.fail(browser)
            }
        if (!navController.safePopBackStack()) marker?.rollback()
    }
}
