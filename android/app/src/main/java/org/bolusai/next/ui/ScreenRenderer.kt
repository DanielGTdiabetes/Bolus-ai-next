package org.bolusai.next.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.bolusai.next.GlucoseStatusMessageKey
import org.bolusai.next.GlucoseStatusPresenter
import org.bolusai.next.R
import org.bolusai.next.application.UnavailableOverview
import org.bolusai.next.navigation.Destination

/** Renders status and navigation only; has no calculation, persistence or integration port. */
internal class ScreenRenderer(
    private val context: Context,
    private val content: LinearLayout,
    private val open: (Destination) -> Unit,
    private val selectSettings: (SettingsSection) -> Unit,
) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = context.getColor(id)
    private fun shape(fill: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(18).toFloat()
        if (border) setStroke(dp(1), color(R.color.status_border))
    }

    private fun text(parent: LinearLayout, label: Int, size: Float = 15f, bold: Boolean = false,
                     ink: Int = R.color.primary_text): TextView = text(parent, context.getString(label), size, bold, ink)

    private fun text(parent: LinearLayout, label: String, size: Float = 15f, bold: Boolean = false,
                     ink: Int = R.color.primary_text): TextView = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(color(ink))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        parent.addView(this)
    }

    private fun heading(label: Int) {
        text(content, label, 20f, true).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    private fun card(parent: LinearLayout = content, fill: Int = R.color.status_background): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            background = shape(fill, border = true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            parent.addView(this)
        }

    private fun info(title: Int, detail: Int, fill: Int = R.color.status_background) {
        card(fill = fill).also {
            text(it, title, 17f, true)
            text(it, detail, ink = R.color.secondary_text)
        }
    }

    private fun button(parent: LinearLayout, label: Int, tagValue: String, action: (() -> Unit)?): Button =
        Button(context).apply {
            setText(label)
            tag = tagValue
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(52)
            minimumHeight = dp(52)
            setTextColor(color(if (action == null) R.color.secondary_text else R.color.accent))
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.accent_surface)),
                shape(if (action == null) R.color.page_background else R.color.status_background, true), null)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            isEnabled = action != null
            if (action != null) setOnClickListener { action() }
            parent.addView(this)
        }

    private fun link(destination: Destination, label: Int = destination.title, parent: LinearLayout = content) {
        button(parent, label, "link:${destination.route}") { open(destination) }.apply {
            text = context.getString(R.string.open_destination, context.getString(label))
        }
    }

    private fun disabled(label: Int, tag: String) { button(content, label, "blocked:$tag", null) }

    fun addTab(parent: LinearLayout, destination: Destination, selected: Boolean, action: () -> Unit) {
        val (symbol, label) = when (destination) {
            Destination.HOME -> "⌂" to R.string.home
            Destination.COMPANION -> "♡" to R.string.companion
            Destination.SCAN -> "◎" to R.string.scan
            Destination.BOLUS -> "+" to R.string.bolus
            Destination.MORE -> "☰" to R.string.more
            else -> error("navigation.not_primary")
        }
        button(parent, label, "tab:${destination.route}", action).apply {
            val expandedLabels = context.resources.configuration.fontScale > 1.3f
            layoutParams = if (expandedLabels) LinearLayout.LayoutParams(-2, -1)
                else LinearLayout.LayoutParams(0, -1, 1f)
            text = context.getString(R.string.tab_label, symbol, context.getString(label))
            contentDescription = context.getString(label)
            gravity = Gravity.CENTER
            textSize = 12f
            val horizontalPadding = if (expandedLabels) dp(16) else dp(2)
            setPadding(horizontalPadding, dp(8), horizontalPadding, dp(8))
            minWidth = 0
            minimumWidth = 0
            isSelected = selected
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.accent_surface)),
                shape(if (selected) R.color.accent_surface else R.color.status_background), null)
            if (selected) {
                setTypeface(typeface, Typeface.BOLD)
                if (expandedLabels) post {
                    requestRectangleOnScreen(android.graphics.Rect(0, 0, width, height), true)
                }
            }
        }
    }

    fun render(destination: Destination, state: UnavailableOverview, settings: SettingsSection) {
        check(!state.allowsCalculation && !state.allowsTreatment)
        content.removeAllViews()
        when (destination) {
            Destination.HOME -> home(state)
            Destination.MORE -> menu()
            Destination.BOLUS, Destination.MANUAL, Destination.OFFLINE_BOLUS -> bolus(state, destination)
            Destination.COMPANION -> {
                info(R.string.alerts_title, R.string.alerts_detail)
                link(Destination.FORECAST)
                link(Destination.BASAL)
                link(Destination.SUGGESTIONS)
                link(Destination.PROFILE)
                link(Destination.SUPPLIES)
            }
            Destination.SCAN -> {
                info(R.string.scan_title, R.string.scan_detail)
                disabled(R.string.camera_disabled, "camera")
                disabled(R.string.gallery_disabled, "gallery")
                link(Destination.SCALE)
                info(R.string.plate_title, R.string.plate_detail)
                link(Destination.BOLUS)
            }
            Destination.SCALE -> {
                info(R.string.scale, R.string.scale_detail)
                disabled(R.string.connect_disabled, "scale_connect")
                disabled(R.string.tare_disabled, "tare")
            }
            Destination.HISTORY -> info(R.string.history_pending, R.string.history_detail)
            Destination.FAVORITES -> {
                info(R.string.favorites, R.string.favorites_detail)
                disabled(R.string.new_meal_disabled, "new_favorite")
            }
            Destination.FOODS -> info(R.string.foods, R.string.foods_detail)
            Destination.FORECAST -> info(R.string.forecast, R.string.forecast_detail)
            Destination.BASAL -> {
                info(R.string.basal, R.string.basal_detail)
                disabled(R.string.basal_disabled, "basal")
            }
            Destination.LEARNING -> info(R.string.learning, R.string.learning_detail)
            Destination.SUGGESTIONS -> info(R.string.suggestions, R.string.suggestions_detail)
            Destination.BODY_MAP -> info(R.string.body_map, R.string.body_map_detail)
            Destination.PROFILE -> {
                info(R.string.sick_mode, R.string.sick_detail)
                info(R.string.profile_account, R.string.profile_account_detail)
                info(R.string.profile_absent, R.string.profile_detail)
                link(Destination.SETTINGS)
            }
            Destination.SUPPLIES -> info(R.string.supplies, R.string.supplies_detail)
            Destination.STATUS -> {
                info(R.string.local_operation, R.string.local_detail)
                info(R.string.authority_title, R.string.authority_detail)
                link(Destination.DIAGNOSTICS)
            }
            Destination.SETTINGS -> settings(state, settings)
            Destination.NIGHTSCOUT -> info(R.string.nightscout, R.string.external_detail)
            Destination.MOBILE -> {
                info(R.string.mobile, R.string.mobile_detail)
                link(Destination.HOME)
                link(Destination.MEALS)
                link(Destination.OFFLINE_BOLUS)
                link(Destination.DIAGNOSTICS)
                link(Destination.MOBILE_SETTINGS)
            }
            Destination.MEALS -> info(R.string.meals, R.string.meals_detail)
            Destination.DIAGNOSTICS -> {
                glucose(state)
                info(R.string.diagnostics, R.string.sync_detail)
                details(state)
            }
            Destination.MOBILE_SETTINGS -> {
                info(R.string.dexcom, R.string.external_detail)
                info(R.string.mfp, R.string.meals_detail)
                link(Destination.MEALS)
                link(Destination.DIAGNOSTICS)
                link(Destination.SETTINGS)
            }
        }
        if (destination !in listOf(Destination.HOME, Destination.MORE, Destination.STATUS)) {
            text(content, R.string.screen_pending, 13f, ink = R.color.secondary_text)
        }
    }

    private fun glucose(state: UnavailableOverview) {
        val model = GlucoseStatusPresenter.present(state.glucose)
        card().also {
            text(it, R.string.glucose_current, 17f, true)
            text(it, glucoseTitle(model.messageKey), ink = R.color.error_text).tag = "glucose_status"
            text(it, R.string.glucose_metadata, 13f, ink = R.color.secondary_text)
        }
    }

    private fun home(state: UnavailableOverview) {
        heading(R.string.overview_intro)
        text(content, R.string.overview_detail, ink = R.color.secondary_text)
        card().also {
            it.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(color(R.color.hero_start), color(R.color.hero_end))).apply { cornerRadius = dp(22).toFloat() }
            text(it, R.string.glucose_current, 15f, true, R.color.on_hero)
            text(it, R.string.glucose_absent, 30f, true, R.color.on_hero)
            text(it, glucoseTitle(GlucoseStatusPresenter.present(state.glucose).messageKey), 14f, ink = R.color.on_hero)
                .tag = "glucose_status"
            text(it, R.string.glucose_metadata, 13f, ink = R.color.on_hero)
            link(Destination.FORECAST, parent = it)
        }
        val metrics = LinearLayout(context).apply {
            orientation = if (context.resources.configuration.fontScale > 1.3f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            content.addView(this)
        }
        listOf(R.string.iob to R.string.unknown, R.string.cob to R.string.unknown,
            R.string.last_bolus to R.string.unavailable).forEach { (label, value) ->
            card(metrics).also {
                if (metrics.orientation == LinearLayout.HORIZONTAL) {
                    it.layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                        marginEnd = dp(4)
                        bottomMargin = dp(14)
                    }
                    it.setPadding(dp(10), dp(14), dp(6), dp(6))
                }
                text(it, label, 13f, true)
                text(it, value, 13f, ink = R.color.secondary_text)
            }
        }
        heading(R.string.quick_actions)
        val actions = listOf(Destination.FAVORITES to R.string.favorites, Destination.BOLUS to R.string.calculate_link,
            Destination.SCALE to R.string.scale, Destination.FOODS to R.string.foods_link)
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { content.addView(this) }
            pair.forEach { (destination, label) ->
                link(destination, label, row)
                row.getChildAt(row.childCount - 1).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(6)
                    bottomMargin = dp(8)
                }
            }
        }
        heading(R.string.recent_activity)
        info(R.string.history_pending, R.string.history_detail)
        link(Destination.HISTORY, R.string.view_all)
        info(R.string.calculation_blocked, R.string.calculation_detail, R.color.non_authoritative_background)
    }

    private fun menu() {
        heading(R.string.menu_intro)
        text(content, R.string.menu_detail, ink = R.color.secondary_text)
        menuSections.forEach { section ->
            heading(section.title)
            section.links.forEach { link(it.destination, it.label) }
        }
    }

    private fun bolus(state: UnavailableOverview, destination: Destination) {
        info(R.string.calculation_blocked,
            if (destination == Destination.BOLUS) R.string.calculation_detail else R.string.manual_detail,
            R.color.non_authoritative_background)
        disabled(R.string.calculate_disabled, "calculate")
        heading(R.string.inputs_title)
        glucose(state)
        info(R.string.iob_absent, R.string.iob_detail)
        info(R.string.profile_absent, R.string.profile_detail)
        link(Destination.SETTINGS)
        heading(R.string.meal_title)
        info(R.string.meal_title, R.string.meal_detail)
        card().also { text(it, R.string.macros_absent, ink = R.color.secondary_text) }
        link(Destination.FAVORITES)
        link(Destination.FOODS)
        link(Destination.MEALS)
        disabled(R.string.confirm_disabled, "confirm")
        details(state)
    }

    private fun settings(state: UnavailableOverview, selected: SettingsSection) {
        val tabs = LinearLayout(context)
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(tabs)
        })
        SettingsSection.entries.forEach { section ->
            button(tabs, section.title, "settings:${section.name}") { selectSettings(section) }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                isSelected = selected == section
                if (isSelected) {
                    background = shape(R.color.accent_surface)
                    post { (tabs.parent as HorizontalScrollView).smoothScrollTo(left, 0) }
                }
            }
        }
        text(content, R.string.settings_detail, 14f, ink = R.color.secondary_text)
        when (selected) {
            SettingsSection.CALCULATION -> {
                info(R.string.clinical_parameters, R.string.clinical_fields)
                disabled(R.string.save_profile_disabled, "save_profile")
            }
            SettingsSection.GLUCOSE, SettingsSection.DEXCOM -> glucose(state)
            SettingsSection.NIGHTSCOUT -> {
                info(R.string.nightscout, R.string.external_detail)
                link(Destination.NIGHTSCOUT)
            }
            SettingsSection.ANALYSIS -> info(R.string.check_isf, R.string.learning_detail)
            SettingsSection.LEARNING -> info(R.string.learning, R.string.learning_detail)
            SettingsSection.DATA -> info(R.string.history_pending, R.string.history_detail)
            SettingsSection.LOGS -> {
                info(R.string.diagnostics, R.string.sync_detail)
                link(Destination.DIAGNOSTICS)
            }
            SettingsSection.VISION, SettingsSection.BOT -> info(selected.title, R.string.external_detail)
        }
    }

    private fun details(state: UnavailableOverview) {
        val codes = text(content, context.getString(R.string.technical_codes, state.glucose.code,
            state.profile.code, state.iob.code, state.meal.code), 12f, ink = R.color.secondary_text)
        codes.visibility = View.GONE
        codes.tag = "blocking_codes"
        codes.setTextIsSelectable(true)
        button(content, R.string.technical_details, "details") {
            codes.isVisible = !codes.isVisible
        }
    }

    private fun glucoseTitle(key: GlucoseStatusMessageKey): Int = when (key) {
        GlucoseStatusMessageKey.MISSING -> R.string.glucose_status_missing
        GlucoseStatusMessageKey.INVALID -> R.string.glucose_status_invalid
        GlucoseStatusMessageKey.EXPIRED -> R.string.glucose_status_expired
        GlucoseStatusMessageKey.UNKNOWN -> R.string.glucose_status_unknown
        GlucoseStatusMessageKey.INCOMPLETE -> R.string.glucose_status_incomplete
        GlucoseStatusMessageKey.CONFLICTING -> R.string.glucose_status_conflicting
        GlucoseStatusMessageKey.PERMISSION_DENIED -> R.string.glucose_status_permission_denied
        GlucoseStatusMessageKey.SOURCE_UNAVAILABLE -> R.string.glucose_status_source_unavailable
        GlucoseStatusMessageKey.AUTHENTICATION_FAILED -> R.string.glucose_status_authentication_failed
        GlucoseStatusMessageKey.CLOCK_ANOMALY -> R.string.glucose_status_clock_anomaly
        GlucoseStatusMessageKey.PARSE_FAILED -> R.string.glucose_status_parse_failed
        GlucoseStatusMessageKey.PERSISTENCE_FAILED -> R.string.glucose_status_persistence_failed
        GlucoseStatusMessageKey.POLICY_NOT_APPROVED -> R.string.glucose_status_policy_not_approved
    }
}
