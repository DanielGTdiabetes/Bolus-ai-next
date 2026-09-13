package org.bolusai.next

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.navigation.AppNavigation
import org.bolusai.next.navigation.Destination
import org.bolusai.next.ui.ScreenRenderer
import org.bolusai.next.ui.SettingsSection
import org.bolusai.next.ui.title

class MainActivity : ComponentActivity() {
    private lateinit var navigation: AppNavigation
    private lateinit var renderer: ScreenRenderer
    private val scrollPositions = mutableMapOf<String, Int>()
    private var settingsSection = SettingsSection.NIGHTSCOUT
    private val overview = ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource))
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = AppNavigation(savedInstanceState?.getStringArrayList("routes").orEmpty())
        settingsSection = SettingsSection.entries.find { it.name == savedInstanceState?.getString("settings") }
            ?: SettingsSection.NIGHTSCOUT
        savedInstanceState?.getBundle("scrolls")?.let { bundle ->
            bundle.keySet().forEach { scrollPositions[it] = bundle.getInt(it) }
        }
        setContentView(R.layout.activity_main)
        renderer = ScreenRenderer(this, findViewById(R.id.screen_content), ::open) {
            settingsSection = it
            render()
        }
        findViewById<Button>(R.id.back_button).setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        render()
    }

    private fun rememberScroll() {
        scrollPositions[navigation.current.route] = findViewById<ScrollView>(R.id.screen_scroll).scrollY
    }

    private fun open(destination: Destination) {
        rememberScroll()
        navigation.open(destination)
        render()
    }

    private fun goBack() {
        rememberScroll()
        if (navigation.back()) render() else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll()
        outState.putStringArrayList("routes", ArrayList(navigation.save()))
        outState.putString("settings", settingsSection.name)
        outState.putBundle("scrolls", Bundle().apply {
            scrollPositions.forEach { (route, position) -> putInt(route, position) }
        })
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val destination = navigation.current
        backCallback.isEnabled = navigation.canGoBack
        findViewById<TextView>(R.id.screen_title).apply {
            setText(destination.title)
            tag = destination.route
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        findViewById<Button>(R.id.back_button).visibility = if (navigation.canGoBack) View.VISIBLE else View.GONE
        renderer.render(destination, overview.execute(), settingsSection)
        val bar = findViewById<LinearLayout>(R.id.bottom_navigation)
        bar.removeAllViews()
        Destination.primary.forEach { tab ->
            renderer.addTab(bar, tab, destination.tab == tab) {
                rememberScroll()
                navigation.selectTab(tab)
                render()
            }
        }
        findViewById<ScrollView>(R.id.screen_scroll).apply {
            post { scrollTo(0, scrollPositions[destination.route] ?: 0) }
        }
    }
}
