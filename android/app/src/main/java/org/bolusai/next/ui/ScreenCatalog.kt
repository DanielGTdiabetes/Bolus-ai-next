package org.bolusai.next.ui

import org.bolusai.next.R
import org.bolusai.next.navigation.Destination

internal val Destination.title: Int
    get() = when (this) {
        Destination.HOME -> R.string.home
        Destination.COMPANION -> R.string.companion
        Destination.SCAN -> R.string.scan_title
        Destination.BOLUS -> R.string.bolus_title
        Destination.MORE -> R.string.more
        Destination.FORECAST -> R.string.forecast
        Destination.BASAL -> R.string.basal
        Destination.FOODS -> R.string.foods
        Destination.FAVORITES -> R.string.favorites
        Destination.HISTORY -> R.string.history
        Destination.LEARNING -> R.string.learning
        Destination.SUGGESTIONS -> R.string.suggestions
        Destination.BODY_MAP -> R.string.body_map
        Destination.PROFILE -> R.string.profile
        Destination.SUPPLIES -> R.string.supplies
        Destination.STATUS -> R.string.status
        Destination.SETTINGS -> R.string.settings
        Destination.MANUAL -> R.string.manual
        Destination.SCALE -> R.string.scale
        Destination.NIGHTSCOUT -> R.string.nightscout
        Destination.MOBILE -> R.string.mobile
        Destination.MEALS -> R.string.meals
        Destination.DIAGNOSTICS -> R.string.diagnostics
        Destination.MOBILE_SETTINGS -> R.string.mobile_settings
        Destination.OFFLINE_BOLUS -> R.string.offline_bolus
    }

internal data class MenuLink(val destination: Destination, val label: Int = destination.title)
internal data class MenuSection(val title: Int, val links: List<MenuLink>)

/** Labels, groups and destinations observed in Legacy MenuPage at the audited SHA. */
internal val menuSections = listOf(
    MenuSection(R.string.group_android, listOf(
        MenuLink(Destination.MOBILE), MenuLink(Destination.DIAGNOSTICS),
        MenuLink(Destination.SCALE), MenuLink(Destination.MOBILE_SETTINGS),
    )),
    MenuSection(R.string.group_tracking, listOf(
        MenuLink(Destination.COMPANION, R.string.my_companion), MenuLink(Destination.FORECAST),
        MenuLink(Destination.BASAL), MenuLink(Destination.SETTINGS, R.string.check_isf),
    )),
    MenuSection(R.string.group_food, listOf(
        MenuLink(Destination.SCAN, R.string.scan_food), MenuLink(Destination.BOLUS, R.string.calculate_bolus_link),
        MenuLink(Destination.FOODS), MenuLink(Destination.FAVORITES),
    )),
    MenuSection(R.string.group_history, listOf(
        MenuLink(Destination.HISTORY), MenuLink(Destination.LEARNING),
        MenuLink(Destination.SUGGESTIONS), MenuLink(Destination.BODY_MAP),
    )),
    MenuSection(R.string.group_account, listOf(
        MenuLink(Destination.PROFILE), MenuLink(Destination.SUPPLIES), MenuLink(Destination.STATUS),
        MenuLink(Destination.SETTINGS), MenuLink(Destination.MANUAL),
    )),
)

internal enum class SettingsSection(val title: Int) {
    NIGHTSCOUT(R.string.nightscout), GLUCOSE(R.string.glucose_sources), DEXCOM(R.string.dexcom),
    CALCULATION(R.string.calc_settings), VISION(R.string.vision), ANALYSIS(R.string.analysis),
    LEARNING(R.string.learning_ml), DATA(R.string.data), BOT(R.string.bot), LOGS(R.string.logs),
}
