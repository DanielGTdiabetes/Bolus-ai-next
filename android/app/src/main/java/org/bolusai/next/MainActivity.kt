package org.bolusai.next

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.bolusai.meals.MealDrafts
import org.bolusai.meals.MealIds
import org.bolusai.meals.MealKind
import org.bolusai.meals.ReadMealHistory
import org.bolusai.meals.ReviewMealSelection
import org.bolusai.next.ui.MealSelectionScreen
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.navigation.AppNavigation
import org.bolusai.next.navigation.Destination
import org.bolusai.next.ui.ScreenRenderer
import org.bolusai.next.ui.SettingsSection
import org.bolusai.next.ui.MealDraftModel
import org.bolusai.next.ui.MealDraftScreen
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.ui.title
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var navigation: AppNavigation
    private lateinit var renderer: ScreenRenderer
    private lateinit var meals: MealDraftModel
    private val scrollPositions = mutableMapOf<String, Int>()
    private var pausedPosition: Pair<Destination, Int>? = null
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
        val restoredEditor = savedInstanceState?.getBundle("mealEditor")
        val restoredHistory = savedInstanceState?.getBundle("mealHistory")
        meals = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = mealRepositoryFactory?.invoke(applicationContext)
                    ?: SqliteMealRepository(applicationContext)
                return MealDraftModel(MealDrafts(repository, MealIds { UUID.randomUUID().toString() }),
                    repository, restoredEditor, ReviewMealSelection(repository), ReadMealHistory(repository),
                    restoredHistory) as T
            }
        })[MealDraftModel::class.java]
        renderer = ScreenRenderer(this, findViewById(R.id.screen_content), ::open,
            { settingsSection = it; render() }, ::renderMeals, ::renderSelection)
        meals.changed = { render() }
        meals.selectionChanged = { if (isReviewDestination(navigation.current)) render() }
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
        refreshReview(destination)
        render()
    }

    private fun goBack() {
        rememberScroll()
        if (isHistoryOpenHere()) {
            // Leave the read-only query first; the library route and any pending editor stay.
            meals.closeHistory()
            return
        }
        if (navigation.back()) { refreshReview(navigation.current); render() } else finish()
    }

    override fun onResume() {
        super.onResume()
        refreshReview(navigation.current)
        val position = pausedPosition
        pausedPosition = null
        if (!isReviewDestination(navigation.current) && position != null) {
            val scroll = findViewById<ScrollView>(R.id.screen_scroll)
            // Restore after native focus/layout traversal without rebuilding the editor or its fields.
            scroll.doOnPreDraw {
                scroll.post {
                    if (navigation.current == position.first) scroll.scrollTo(0, position.second)
                }
            }
        }
    }

    override fun onPause() {
        rememberScroll()
        pausedPosition = navigation.current to findViewById<ScrollView>(R.id.screen_scroll).scrollY
        super.onPause()
    }

    private fun isHistoryOpenHere(): Boolean {
        val target = meals.historyTarget ?: return false
        return (navigation.current == Destination.FAVORITES && target.kind == MealKind.DISH) ||
            (navigation.current == Destination.MEALS && target.kind == MealKind.DRAFT)
    }

    private fun isReviewDestination(destination: Destination): Boolean =
        destination in listOf(Destination.BOLUS, Destination.MANUAL, Destination.OFFLINE_BOLUS)

    private fun refreshReview(destination: Destination) {
        if (isReviewDestination(destination)) meals.refreshSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll()
        outState.putStringArrayList("routes", ArrayList(navigation.save()))
        outState.putString("settings", settingsSection.name)
        outState.putBundle("scrolls", Bundle().apply {
            scrollPositions.forEach { (route, position) -> putInt(route, position) }
        })
        meals.snapshot()?.let { outState.putBundle("mealEditor", it) }
        meals.historySnapshot()?.let { outState.putBundle("mealHistory", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        meals.changed = null
        meals.selectionChanged = null
        super.onDestroy()
    }

    private fun renderMeals(kind: MealKind) {
        MealDraftScreen(this, findViewById(R.id.screen_content), meals, { open(Destination.BOLUS) }) {
            if (navigation.current != Destination.MEALS) open(Destination.MEALS) else render()
        }.render(kind)
    }

    private fun renderSelection() {
        MealSelectionScreen(this, findViewById(R.id.screen_content), meals) { record ->
            val edit = {
                meals.edit(record)
                open(if (record.kind == MealKind.DISH) Destination.FAVORITES else Destination.MEALS)
            }
            if (meals.dirty) android.app.AlertDialog.Builder(this).setMessage(R.string.draft_discard_question)
                .setNegativeButton(R.string.draft_keep, null)
                .setPositiveButton(R.string.draft_discard) { _, _ -> edit() }.show()
            else edit()
        }.render()
    }

    private fun render() {
        val destination = navigation.current
        val canGoBack = navigation.canGoBack || isHistoryOpenHere()
        backCallback.isEnabled = canGoBack
        findViewById<TextView>(R.id.screen_title).apply {
            setText(destination.title)
            tag = destination.route
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        findViewById<Button>(R.id.back_button).visibility = if (canGoBack) View.VISIBLE else View.GONE
        renderer.render(destination, overview.execute(), settingsSection)
        val bar = findViewById<LinearLayout>(R.id.bottom_navigation)
        bar.removeAllViews()
        Destination.primary.forEach { tab ->
            renderer.addTab(bar, tab, destination.tab == tab) {
                rememberScroll()
                navigation.selectTab(tab)
                refreshReview(tab)
                render()
            }
        }
        findViewById<ScrollView>(R.id.screen_scroll).apply {
            post { scrollTo(0, scrollPositions[destination.route] ?: 0) }
        }
    }

    internal companion object {
        /** Process-local test seam. No Intent or external caller can select volatile storage. */
        var mealRepositoryFactory: ((Context) -> SqliteMealRepository)? = null
    }
}
