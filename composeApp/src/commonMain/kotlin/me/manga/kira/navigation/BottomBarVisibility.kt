package me.manga.kira.navigation

private val bottomBarRoutes =
    setOf(
        Screen.Library.route,
        Screen.Updates.route,
        Screen.Home.route,
        Screen.History.route,
        Screen.Setting.route,
    )

/** Returns whether any route in the active destination hierarchy owns the primary bottom bar. */
internal fun shouldShowBottomBar(destinationHierarchyRoutes: Sequence<String?>): Boolean =
    destinationHierarchyRoutes.any { route -> route in bottomBarRoutes }
