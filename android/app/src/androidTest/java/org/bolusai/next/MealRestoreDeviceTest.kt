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

/** Explicit restoration (ADR 0011). Isolated synthetic repositories only; the app's meal database is never opened. */
@RunWith(AndroidJUnit4::class)
class MealRestoreDeviceTest {
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

    private fun seed(name: String, id: String, kind: MealKind): Pair<MealRecord, MealRecord> =
        SqliteMealRepository(context, name).use {
            val first = (it.save(MealRecord(id, 0, kind,
                MealContent(name = draftField("Plato sintético"), carbs = draftField("0"), notes = draftField("  exacto  ")),
                if (kind == MealKind.DRAFT) DishReference("restore-source", 5) else null)) as MealSave.Saved).record
            val second = (it.save(first.copy(content = first.content.copy(carbs = draftField("texto nuevo"),
                fat = draftField("0"), basis = NutritionBasis.TOTAL_GRAMS))) as MealSave.Saved).record
            assertEquals(MealSelection.Reviewed(second, second), it.select(second))
            first to second
        }

    @Test fun confirmedRestoreSavesNextRevisionWithProvenanceAndKeepsBolusSelection() {
        val name = "synthetic-restore-${UUID.randomUUID()}.db"
        val id = "restore-ui"
        val (first, second) = seed(name, id, MealKind.DISH)
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                openBolusTab(scenario)
                awaitView(scenario, "selection:carbs", "texto nuevo")
                scenario.onActivity { view(it, "link:/favorites").performClick() }
                awaitView(scenario, "meal:history:$id")
                scenario.onActivity { view(it, "meal:history:$id").performClick() }
                awaitView(scenario, "history:1:restore")
                scenario.onActivity {
                    // The latest revision cannot be restored; the query itself writes nothing.
                    assertNull(viewOrNull(it, "history:2:restore"))
                    view(it, "history:1:restore").performClick()
                }
                awaitView(scenario, "restore:question", context.getString(R.string.meal_restore_question, 1, 3))
                scenario.onActivity {
                    assertEquals(context.getString(R.string.meal_restore_confirm, 1), text(it, "restore:confirm"))
                    assertNull(viewOrNull(it, "meal:save"))
                }
                // The pending confirmation survives recreation and cancelling leaves the query untouched.
                scenario.recreate()
                awaitView(scenario, "restore:question", context.getString(R.string.meal_restore_question, 1, 3))
                scenario.onActivity { view(it, "restore:cancel").performClick() }
                awaitView(scenario, "history:1:restore")
                scenario.onActivity {
                    assertNull(viewOrNull(it, "restore:question"))
                    view(it, "history:1:restore").performClick()
                }
                awaitView(scenario, "restore:confirm")
                scenario.onActivity { view(it, "restore:confirm").performClick() }
                awaitView(scenario, "meal:restored_from", context.getString(R.string.meal_restored_editor, 1, 3))
                val assertRestoredEditor = { activity: MainActivity ->
                    assertEquals("0", text(activity, "meal:carbs"))
                    assertEquals("", text(activity, "meal:fat"))
                    assertEquals("  exacto  ", text(activity, "meal:notes"))
                    assertTrue(view(activity, "meal:save").isEnabled)
                    assertNull(viewOrNull(activity, "history:status"))
                    assertEquals(context.getString(R.string.dish_unsaved), text(activity, "meal:status"))
                }
                scenario.onActivity { assertRestoredEditor(it) }
                scenario.recreate()
                awaitView(scenario, "meal:restored_from", context.getString(R.string.meal_restored_editor, 1, 3))
                scenario.onActivity { assertRestoredEditor(it) }
                // Nothing was written before the explicit save.
                SqliteMealRepository(context, name).use {
                    assertEquals(MealRead.Loaded(listOf(first, second)), it.readRevisions(id))
                    assertEquals(MealSelection.Reviewed(second, second), it.readSelection())
                }
                scenario.onActivity { view(it, "meal:save").performClick() }
                awaitView(scenario, "meal:status", context.getString(R.string.dish_saved))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "meal:restored_from"))
                    view(it, "meal:history").performClick()
                }
                awaitView(scenario, "history:3:restored_from", context.getString(R.string.meal_history_restored_from, 1))
                scenario.onActivity { assertNull(viewOrNull(it, "history:3:restore")) }
                scenario.onActivity { view(it, "history:close").performClick() }
                openBolusTab(scenario)
                awaitView(scenario, "selection:block", "meal.selection.obsolete")
                scenario.onActivity {
                    assertFalse(view(it, "blocked:calculate").isEnabled)
                    assertFalse(view(it, "blocked:confirm").isEnabled)
                }
            }
            SqliteMealRepository(context, name).use {
                val third = MealRecord(id, 3, MealKind.DISH, first.content, restoredFrom = 1)
                assertEquals(MealRead.Loaded(listOf(first, second, third)), it.readRevisions(id))
                // The stored selection still points at revision 2; it is only reported obsolete.
                assertEquals(MealSelection.Reviewed(second, third), it.readSelection())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pendingEditorRequiresDiscardAndCancelKeepsIt() {
        val name = "synthetic-restore-pending-${UUID.randomUUID()}.db"
        val id = "restore-pending"
        val (first, second) = seed(name, id, MealKind.DRAFT)
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                openBolusTab(scenario)
                awaitView(scenario, "selection:carbs", "texto nuevo")
                scenario.onActivity { view(it, "link:native/meals").performClick() }
                awaitView(scenario, "meal:edit:$id")
                scenario.onActivity { view(it, "meal:edit:$id").performClick() }
                awaitView(scenario, "meal:history")
                scenario.onActivity {
                    (view(it, "meal:notes") as EditText).apply { text.clear(); append("Texto sintético sin guardar") }
                    view(it, "meal:history").performClick()
                }
                awaitView(scenario, "history:1:restore")
                scenario.onActivity { view(it, "history:1:restore").performClick() }
                awaitView(scenario, "restore:question", context.getString(R.string.meal_restore_question, 1, 3) +
                    "\n" + context.getString(R.string.meal_restore_discard_warning))
                scenario.onActivity {
                    assertEquals(context.getString(R.string.meal_restore_confirm_discard, 1), text(it, "restore:confirm"))
                    view(it, "restore:cancel").performClick()
                    view(it, "history:close").performClick()
                }
                awaitView(scenario, "meal:notes", "Texto sintético sin guardar")
                scenario.onActivity {
                    assertNull(viewOrNull(it, "meal:restored_from"))
                    view(it, "meal:history").performClick()
                }
                awaitView(scenario, "history:1:restore")
                scenario.onActivity { view(it, "history:1:restore").performClick() }
                awaitView(scenario, "restore:confirm")
                scenario.onActivity { view(it, "restore:confirm").performClick() }
                awaitView(scenario, "meal:notes", "  exacto  ")
                scenario.onActivity {
                    // The discarded text is gone; the editor holds revision 1 exactly.
                    assertEquals("0", text(it, "meal:carbs"))
                    assertEquals("", text(it, "meal:fat"))
                    assertEquals(context.getString(R.string.meal_restored_editor, 1, 3), text(it, "meal:restored_from"))
                    assertTrue(view(it, "meal:save").isEnabled)
                }
            }
            SqliteMealRepository(context, name).use {
                assertEquals(MealRead.Loaded(listOf(first, second)), it.readRevisions(id))
                assertEquals(MealSelection.Reviewed(second, second), it.readSelection())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun laterRevisionMakesRestoredSaveAVisibleConflictWithoutOverwriting() {
        val name = "synthetic-restore-conflict-${UUID.randomUUID()}.db"
        val id = "restore-conflict"
        val (first, second) = seed(name, id, MealKind.DRAFT)
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        lateinit var external: MealRecord
        try {
            launch().use { scenario ->
                openBolusTab(scenario)
                awaitView(scenario, "selection:carbs", "texto nuevo")
                scenario.onActivity { view(it, "link:native/meals").performClick() }
                awaitView(scenario, "meal:history:$id")
                scenario.onActivity { view(it, "meal:history:$id").performClick() }
                awaitView(scenario, "history:1:restore")
                scenario.onActivity { view(it, "history:1:restore").performClick() }
                awaitView(scenario, "restore:confirm")
                scenario.onActivity { view(it, "restore:confirm").performClick() }
                awaitView(scenario, "meal:restored_from")
                external = SqliteMealRepository(context, name).use {
                    (it.save(second.copy(content = second.content.copy(name = draftField("otra escritura")))) as MealSave.Saved).record
                }
                scenario.onActivity { view(it, "meal:save").performClick() }
                awaitView(scenario, "meal:status",
                    context.getString(R.string.draft_save_failed, MealFailure.CONFLICT.code))
                scenario.onActivity {
                    assertEquals("0", text(it, "meal:carbs"))
                    assertEquals(context.getString(R.string.meal_restored_editor, 1, 3), text(it, "meal:restored_from"))
                }
            }
            SqliteMealRepository(context, name).use {
                assertEquals(MealRead.Loaded(listOf(first, second, external)), it.readRevisions(id))
                assertNull(external.restoredFrom)
                assertEquals(MealSelection.Reviewed(second, external), it.readSelection())
            }
        } finally { context.deleteDatabase(name) }
    }

    private class LoadedHistory(private val records: List<MealRecord>) :
        MealRepository, MealSelectionRepository, MealHistoryRepository {
        override fun read(kind: MealKind) = MealRead.Loaded(emptyList())
        override fun save(editor: MealRecord): MealSave = error("Layout check must not save")
        override fun readSelection(): MealSelection = error("Restoring must not read the selection")
        override fun select(record: MealRecord): MealSelection = error("Restoring must not select")
        override fun clearSelection(): MealSelection = error("Restoring must not clear the selection")
        override fun readRevisions(id: String) = MealRead.Loaded(records)
    }

    @Test fun restoreConfirmationFitsExpandedLayoutsAtLargeFontWithoutClipping() {
        val older = MealRecord("restore-fold", 1, MealKind.DRAFT,
            MealContent(name = draftField("Plato sintético para restaurar en pantalla interior"), carbs = draftField("0")),
            DishReference("synthetic-source", 3))
        val latest = older.copy(revision = 2, content = older.content.copy(protein = draftField("sin validar")))
        val repository = LoadedHistory(listOf(older, latest))
        val loaded = CountDownLatch(1)
        lateinit var model: MealDraftModel
        instrumentation.runOnMainSync {
            model = MealDraftModel(MealDrafts(repository, MealIds { "restore-model" }), Closeable {}, null,
                ReviewMealSelection(repository), ReadMealHistory(repository))
            model.changed = { if (model.history != null) loaded.countDown() }
            model.openHistory(older.id, MealKind.DRAFT)
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                model.changed = null
                model.proposeRestore(1)
                assertEquals(1L, model.restoreCandidate)
            }
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
                            assertNotNull(panel.findViewWithTag<View>("restore:confirm"))
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
        } finally { instrumentation.runOnMainSync { model.dispose() } }
    }
}
