package org.bolusai.next.navigation

/** UI destinations only. No clinical state, URL loading or provider actions. */
internal enum class Destination(val route: String) {
    HOME("/"), COMPANION("/notifications"), SCAN("/scan"), BOLUS("/bolus"), MORE("/menu"),
    FORECAST("/forecast"), BASAL("/basal"), FOODS("/food-db"), FAVORITES("/favorites"),
    HISTORY("/history"), LEARNING("/learning"), SUGGESTIONS("/suggestions"), BODY_MAP("/bodymap"),
    PROFILE("/profile"), SUPPLIES("/supplies"), STATUS("/status"), SETTINGS("/settings"),
    MANUAL("/manual"), SCALE("/scale"), NIGHTSCOUT("/nightscout-settings"),
    MOBILE("native/mobile"), MEALS("native/meals"), DIAGNOSTICS("native/diagnostics"),
    MOBILE_SETTINGS("native/settings"), OFFLINE_BOLUS("native/bolus");

    val tab: Destination
        get() = when (this) {
            HOME -> HOME
            COMPANION, FORECAST, BASAL, SUGGESTIONS -> COMPANION
            SCAN, SCALE -> SCAN
            BOLUS -> BOLUS
            else -> MORE
        }

    companion object {
        val primary = listOf(HOME, COMPANION, SCAN, BOLUS, MORE)
    }
}

/** Selecting a main tab resets its path; child links retain their actual caller. */
internal class AppNavigation(savedRoutes: List<String> = emptyList()) {
    private val stack = mutableListOf(Destination.HOME)

    init {
        val restored = savedRoutes.map { route -> Destination.entries.find { it.route == route } }
        if (restored.isNotEmpty() && restored.first() == Destination.HOME && restored.all { it != null }) {
            stack.clear()
            stack.addAll(restored.filterNotNull())
        }
    }

    val current: Destination get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun save(): List<String> = stack.map { it.route }

    fun selectTab(destination: Destination) {
        require(destination in Destination.primary)
        stack.clear()
        stack.add(Destination.HOME)
        if (destination != Destination.HOME) stack.add(destination)
    }

    fun open(destination: Destination) {
        if (destination == Destination.HOME) selectTab(destination)
        else if (current != destination) stack.add(destination)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
