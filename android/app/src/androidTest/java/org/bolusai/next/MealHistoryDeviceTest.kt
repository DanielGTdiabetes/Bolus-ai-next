package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.*
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.ui.MealDraftModel
import org.bolusai.next.ui.MealDraftScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only revision query. Isolated synthetic repositories only; the app's meal database is never opened. */
@RunWith(AndroidJUnit4::class)
class MealHistoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                // Test window only; no keyguard or device-setting changes.
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                }
            }
        }

    @After fun clearFactory() { MainActivity.mealRepositoryFactory = null }

    private fun viewOrNull(activity: MainActivity, tag: String): View? =
        activity.findViewById<View>(R.id.screen_content).findViewWithTag(tag)
    private fun view(activity: MainActivity, tag: String): View = requireNotNull(viewOrNull(activity, tag)) { tag }
    private fun text(activity: MainActivity, tag: String) = (view(activity, tag) as TextView).text.toString()

    private fun awaitView(scenario: ActivityScenario<MainActivity>, tag: String, expected: String? = null) {
        val limit = SystemClock.uptimeMillis() + 5_000
        var actual: String? = null
        while (SystemClock.uptimeMillis() < limit) {
            var found = false
            scenario.onActivity { activity ->
                val view = viewOrNull(activity, tag)
                actual = (view as? TextView)?.text?.toString()
                found = view != null && (expected == null || actual == expected)
            }
            if (found) return
            SystemClock.sleep(25)
        }
        fail("Missing synthetic UI state: $tag, expected '$expected', got '$actual'")
    }

    private fun openBolusTab(scenario: ActivityScenario<MainActivity>) = scenario.onActivity {
        it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/bolus").performClick()
    }

    @Test fun dishHistoryIsReadOnlyAndKeepsObsoleteBolusSelection() = libraryHistory(MealKind.DISH)
    @Test fun draftHistoryIsReadOnlyAndKeepsObsoleteBolusSelection() = libraryHistory(MealKind.DRAFT)

    private fun libraryHistory(kind: MealKind) {
        val name = "synthetic-history-${UUID.randomUUID()}.db"
        val id = "history-ui"
        lateinit var first: MealRecord
        lateinit var second: MealRecord
        SqliteMealRepository(context, name).use {
            first = (it.save(MealRecord(id, 0, kind,
                MealContent(name = draftField("Plato sintético"), carbs = draftField("0"),
                    notes = draftField("  exacto  ")),
                if (kind == MealKind.DRAFT) DishReference("history-source", 5) else null)) as MealSave.Saved).record
            it.select(first)
            second = (it.save(first.copy(content = first.content.copy(carbs = draftField("texto nuevo"),
                fat = draftField("0"), basis = NutritionBasis.TOTAL_GRAMS))) as MealSave.Saved).record
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                openBolusTab(scenario)
                awaitView(scenario, "selection:block", "meal.selection.obsolete")
                scenario.onActivity { view(it, if (kind == MealKind.DISH) "link:/favorites" else "link:native/meals").performClick() }
                awaitView(scenario, "meal:history:$id")
                scenario.onActivity { view(it, "meal:history:$id").performClick() }
                awaitView(scenario, "history:2:carbs", "texto nuevo")
                val assertHistory = { activity: MainActivity ->
                    assertEquals(context.getString(R.string.meal_history_status, 2, 2), text(activity, "history:status"))
                    assertEquals(context.getString(R.string.meal_history_latest, 2), text(activity, "history:2"))
                    assertEquals(context.getString(R.string.meal_history_revision, 1), text(activity, "history:1"))
                    assertEquals("0", text(activity, "history:1:carbs"))
                    assertEquals(context.getString(R.string.draft_missing), text(activity, "history:1:fat"))
                    assertEquals("0", text(activity, "history:2:fat"))
                    assertEquals("  exacto  ", text(activity, "history:1:notes"))
                    assertEquals(context.getString(R.string.meal_selection_basis_missing), text(activity, "history:1:basis"))
                    assertEquals(context.getString(R.string.meal_selection_grams), text(activity, "history:2:basis"))
                    assertEquals(if (kind == MealKind.DRAFT) context.getString(R.string.meal_selection_copy, "history-source", 5)
                        else context.getString(R.string.meal_selection_manual), text(activity, "history:origin"))
                    assertTrue(text(activity, "history:identity").contains(id))
                    assertEquals("meal.history.read_only", text(activity, "history:block"))
                    // Newest first is the declared order.
                    val panel = activity.findViewById<android.widget.LinearLayout>(R.id.screen_content)
                    assertTrue(panel.indexOfChild(view(activity, "history:2")) < panel.indexOfChild(view(activity, "history:1")))
                    // No selection, editing, restoration or deletion from the query.
                    listOf("meal:select", "meal:select:$id", "meal:edit:$id", "meal:save", "meal:new").forEach {
                        assertNull(it, viewOrNull(activity, it))
                    }
                    assertEquals(context.getString(R.string.draft_back_to_list), text(activity, "history:close"))
                }
                scenario.onActivity { assertHistory(it) }
                scenario.recreate()
                awaitView(scenario, "history:2:carbs", "texto nuevo")
                scenario.onActivity { assertHistory(it) }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                awaitView(scenario, "meal:history:$id")
                scenario.onActivity { assertNull(viewOrNull(it, "history:status")) }
                openBolusTab(scenario)
                awaitView(scenario, "selection:block", "meal.selection.obsolete")
                awaitView(scenario, "selection:carbs", "0")
                scenario.onActivity {
                    assertFalse(view(it, "blocked:calculate").isEnabled)
                    assertFalse(view(it, "blocked:confirm").isEnabled)
                }
            }
            SqliteMealRepository(context, name).use {
                assertEquals(MealRead.Loaded(listOf(first, second)), it.readRevisions(id))
                assertEquals(MealSelection.Reviewed(first, second), it.readSelection())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun historyFromEditorReturnsToUnsavedEditorWithoutSaving() {
        val name = "synthetic-history-editor-${UUID.randomUUID()}.db"
        val record = SqliteMealRepository(context, name).use {
            (it.save(MealRecord("history-editor", 0, MealKind.DISH, MealContent(carbs = draftField("0")))) as MealSave.Saved).record
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                openBolusTab(scenario)
                awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                scenario.onActivity { view(it, "link:/favorites").performClick() }
                awaitView(scenario, "meal:edit:${record.id}")
                scenario.onActivity { view(it, "meal:edit:${record.id}").performClick() }
                awaitView(scenario, "meal:history")
                scenario.onActivity {
                    (view(it, "meal:notes") as EditText).append("Texto sintético sin guardar")
                    view(it, "meal:history").performClick()
                }
                awaitView(scenario, "history:1:carbs", "0")
                scenario.onActivity {
                    assertEquals(context.getString(R.string.draft_missing), text(it, "history:1:notes"))
                    assertEquals(context.getString(R.string.meal_history_back_to_editor), text(it, "history:close"))
                    assertNull(viewOrNull(it, "meal:notes"))
                }
                scenario.recreate()
                awaitView(scenario, "history:1:carbs", "0")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                awaitView(scenario, "meal:notes", "Texto sintético sin guardar")
                scenario.onActivity {
                    assertTrue(view(it, "meal:save").isEnabled)
                    view(it, "meal:history").performClick()
                }
                awaitView(scenario, "history:close")
                scenario.onActivity { view(it, "history:close").performClick() }
                awaitView(scenario, "meal:notes", "Texto sintético sin guardar")
            }
            SqliteMealRepository(context, name).use {
                assertEquals(MealRead.Loaded(listOf(record)), it.readRevisions(record.id))
                assertEquals(MealSelection.Missing, it.readSelection())
            }
        } finally { context.deleteDatabase(name) }
    }

    private class ScriptedHistory(vararg results: MealRead) : MealRepository, MealSelectionRepository, MealHistoryRepository {
        val queue = ArrayDeque(results.toList())
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        var gate: CountDownLatch? = null
        var started: CountDownLatch? = null
        override fun read(kind: MealKind) = MealRead.Loaded(emptyList())
        override fun save(editor: MealRecord): MealSave = error("History must not save")
        override fun readSelection(): MealSelection = error("History must not read the selection")
        override fun select(record: MealRecord): MealSelection = error("History must not select")
        override fun clearSelection(): MealSelection = error("History must not clear the selection")
        override fun readRevisions(id: String): MealRead {
            reads.incrementAndGet()
            started?.countDown()
            gate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            return synchronized(queue) { queue.removeFirst() }
        }
    }

    private fun model(repository: ScriptedHistory) = MealDraftModel(MealDrafts(repository, MealIds { "history-model" }),
        Closeable {}, null, ReviewMealSelection(repository), ReadMealHistory(repository))

    @Test fun failedAndMissingReadsStayExplicitAndRetryIsExplicit() {
        val record = MealRecord("history-scripted", 1, MealKind.DRAFT, MealContent(carbs = draftField("0")))
        val repository = ScriptedHistory(MealRead.Failed(MealFailure.READ_FAILED),
            MealRead.Loaded(listOf(record)), MealRead.Loaded(emptyList()))
        lateinit var model: MealDraftModel
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        try {
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    model = model(repository)
                    lateinit var screen: MealDraftScreen
                    screen = MealDraftScreen(activity, activity.findViewById(R.id.screen_content), model) {}
                    model.changed = { screen.render(MealKind.DRAFT) }
                    model.openHistory(record.id, MealKind.DRAFT)
                }
                awaitView(scenario, "history:status",
                    context.getString(R.string.meal_history_failed, MealFailure.READ_FAILED.code))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "history:1:carbs"))
                    assertNull(viewOrNull(it, "history:block"))
                    assertEquals(1, repository.reads.get())
                    view(it, "history:retry").performClick()
                }
                awaitView(scenario, "history:1:carbs", "0")
                scenario.onActivity {
                    assertNull(viewOrNull(it, "history:retry"))
                    model.openHistory("history-absent", MealKind.DRAFT)
                }
                awaitView(scenario, "history:status", context.getString(R.string.meal_history_missing, "history-absent"))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "history:1:carbs"))
                    assertEquals(MealHistory.Missing, model.history)
                    assertFalse(requireNotNull(model.history).allowsCalculation)
                    assertNull(model.selection)
                }
                assertEquals(3, repository.reads.get())
            }
        } finally { instrumentation.runOnMainSync { model.dispose() } }
    }

    @Test fun closedOrReplacedQueryCannotShowALateResult() {
        val stale = MealRecord("history-stale", 1, MealKind.DISH, MealContent(carbs = draftField("antiguo")))
        val fresh = MealRecord("history-fresh", 1, MealKind.DISH, MealContent(carbs = draftField("0")))
        val repository = ScriptedHistory(MealRead.Loaded(listOf(stale)), MealRead.Loaded(listOf(fresh)))
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        repository.gate = gate
        repository.started = started
        val loaded = CountDownLatch(1)
        lateinit var model: MealDraftModel
        instrumentation.runOnMainSync {
            model = model(repository)
            model.changed = { if (model.history != null) loaded.countDown() }
            model.openHistory(stale.id, MealKind.DISH)
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                model.closeHistory()
                assertNull(model.historyTarget)
                model.openHistory(fresh.id, MealKind.DISH)
                assertNull(model.history)
            }
            repository.started = null
            gate.countDown()
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val history = model.history as MealHistory.Loaded
                assertEquals(fresh.id, history.id)
                assertEquals(listOf(fresh), history.revisions)
                model.closeHistory()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNull(model.history)
                assertNull(model.historyTarget)
            }
            assertEquals(2, repository.reads.get())
        } finally {
            gate.countDown()
            instrumentation.runOnMainSync { model.dispose() }
        }
    }

    @Test fun historyFitsExpandedLayoutsAtLargeFontWithoutClipping() {
        val older = MealRecord("history-fold", 1, MealKind.DRAFT,
            MealContent(name = draftField("Plato sintético para revisar en pantalla interior"), carbs = draftField("0")),
            DishReference("synthetic-source", 3))
        val latest = older.copy(revision = 2, content = older.content.copy(protein = draftField("sin validar"),
            basis = NutritionBasis.TOTAL_GRAMS))
        val repository = ScriptedHistory(MealRead.Loaded(listOf(older, latest)))
        val loaded = CountDownLatch(1)
        lateinit var model: MealDraftModel
        instrumentation.runOnMainSync {
            model = model(repository)
            model.changed = { if (model.history != null) loaded.countDown() }
            model.openHistory(older.id, MealKind.DRAFT)
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    fun descendants(root: View): List<View> = listOf(root) + if (root is android.view.ViewGroup)
                        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
                    // Representative expanded portrait/landscape viewports, not a claim about hardware pixels.
                    for ((widthDp, heightDp) in listOf(840 to 900, 900 to 840, 411 to 914)) {
                        for (scale in listOf(1f, 1.8f)) {
                            val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                                screenWidthDp = widthDp; screenHeightDp = heightDp; fontScale = scale
                            }
                            val themed = activity.createConfigurationContext(configuration)
                            val shell = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
                            val panel = shell.findViewById<android.widget.LinearLayout>(R.id.screen_content)
                            shell.findViewById<TextView>(R.id.screen_title).setText(R.string.meal_history_title)
                            MealDraftScreen(themed, panel, model) {}.render(MealKind.DRAFT)
                            val density = themed.resources.displayMetrics.density
                            val width = (widthDp * density).toInt()
                            val height = (heightDp * density).toInt()
                            shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            shell.layout(0, 0, width, height)
                            assertTrue(shell.findViewById<View>(R.id.screen_scroll).height > 0)
                            assertEquals("0", panel.findViewWithTag<TextView>("history:1:carbs").text.toString())
                            descendants(shell).filterIsInstance<TextView>().filter { it.isVisible }.forEach {
                                assertTrue("No width: ${it.tag}", it.width > 0)
                                assertTrue("Clipped text: ${it.tag}", it.layout.height <=
                                    it.height - it.compoundPaddingTop - it.compoundPaddingBottom)
                                for (line in 0 until it.layout.lineCount) assertEquals(0, it.layout.getEllipsisCount(line))
                            }
                        }
                    }
                }
            }
            assertEquals(1, repository.reads.get())
        } finally { instrumentation.runOnMainSync { model.dispose() } }
    }
}
