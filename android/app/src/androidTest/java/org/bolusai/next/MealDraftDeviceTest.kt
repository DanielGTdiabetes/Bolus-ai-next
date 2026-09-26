package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.*
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.ui.MealDraftModel
import org.bolusai.next.ui.MealDraftScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.UUID

/** Uses isolated synthetic repositories; it never reads or deletes the app's normal meal database. */
@RunWith(AndroidJUnit4::class)
class MealDraftDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun launch(): ActivityScenario<MainActivity> {
        if (MainActivity.mealRepositoryFactory == null) {
            MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                // Test window only, as in NavigationDeviceTest; no keyguard or device-setting changes.
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                }
            }
        }
    }

    @After fun clearFactory() { MainActivity.mealRepositoryFactory = null }

    private fun viewOrNull(activity: MainActivity, tag: String): View? =
        activity.findViewById<View>(R.id.screen_content).findViewWithTag(tag)
    private fun view(activity: MainActivity, tag: String): View = requireNotNull(viewOrNull(activity, tag))

    private fun awaitStatus(scenario: ActivityScenario<MainActivity>, expected: String) {
        val limit = SystemClock.uptimeMillis() + 5_000
        var actual = ""
        while (SystemClock.uptimeMillis() < limit) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { actual = (view(it, "meal:status") as TextView).text.toString() }
            if (actual == expected) return
            SystemClock.sleep(25)
        }
        fail("Expected status '$expected', got '$actual'")
    }

    private fun awaitView(scenario: ActivityScenario<MainActivity>, tag: String, text: String? = null) {
        val limit = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < limit) {
            var found = false
            scenario.onActivity { activity ->
                val view = viewOrNull(activity, tag)
                found = view != null && (text == null || (view as TextView).text.toString() == text)
            }
            if (found) return
            SystemClock.sleep(25)
        }
        fail("Missing synthetic UI state: $tag")
    }

    @Test fun dishSelectionSurvivesRelaunchAndRequiresExplicitNewRevision() = selectionFlow(MealKind.DISH)
    @Test fun draftSelectionSurvivesRelaunchAndRequiresExplicitNewRevision() = selectionFlow(MealKind.DRAFT)

    private fun selectionFlow(kind: MealKind) {
        val context = instrumentation.targetContext
        val name = "synthetic-selection-${UUID.randomUUID()}.db"
        val record = SqliteMealRepository(context, name).use {
            (it.save(MealRecord("selection-ui", 0, kind,
                MealContent(name = draftField("Plato sintético"), carbs = draftField("0"),
                    notes = draftField("  exacto  ")),
                if (kind == MealKind.DRAFT) DishReference("source-ui", 5) else null)) as MealSave.Saved).record
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.bottom_navigation)
                    .findViewWithTag<View>("tab:/bolus").performClick() }
                awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                scenario.onActivity { view(it, if (kind == MealKind.DISH) "link:/favorites" else "link:native/meals").performClick() }
                awaitView(scenario, "meal:select:${record.id}")
                scenario.onActivity { view(it, "meal:select:${record.id}").performClick() }
                awaitView(scenario, "selection:carbs", "0")
                scenario.onActivity { activity ->
                    assertEquals("  exacto  ", (view(activity, "selection:notes") as TextView).text.toString())
                    assertEquals(context.getString(R.string.draft_missing), (view(activity, "selection:fat") as TextView).text.toString())
                    assertEquals(context.getString(R.string.meal_selection_basis_missing), (view(activity, "selection:basis") as TextView).text.toString())
                    assertTrue((view(activity, "selection:identity") as TextView).text.contains(record.id))
                    if (kind == MealKind.DRAFT) assertEquals(context.getString(R.string.meal_selection_copy, "source-ui", 5),
                        (view(activity, "selection:origin") as TextView).text.toString())
                    assertFalse(view(activity, "blocked:calculate").isEnabled)
                    assertFalse(view(activity, "blocked:confirm").isEnabled)
                }
                scenario.recreate()
                awaitView(scenario, "selection:carbs", "0")
                scenario.onActivity { view(it, "selection:edit").performClick() }
                awaitView(scenario, "meal:carbs")
                scenario.onActivity { activity ->
                    (view(activity, "meal:carbs") as EditText).apply { text.replace(0, text.length, "nuevo texto") }
                    assertFalse(view(activity, "meal:select").isEnabled)
                    view(activity, "meal:save").performClick()
                }
                awaitStatus(scenario, context.getString(if (kind == MealKind.DISH) R.string.dish_saved else R.string.draft_saved))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                awaitView(scenario, "selection:block", "meal.selection.obsolete")
                awaitView(scenario, "selection:carbs", "0")
            }
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                    .findViewWithTag<View>("tab:/bolus").performClick() }
                awaitView(scenario, "selection:block", "meal.selection.obsolete")
                scenario.onActivity { view(it, "selection:edit").performClick() }
                awaitView(scenario, "meal:select")
                scenario.onActivity { view(it, "meal:select").performClick() }
                awaitView(scenario, "selection:carbs", "nuevo texto")
                awaitView(scenario, "selection:block", "meal.draft.not_clinically_validated")
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedSelectionReadIsVisibleAndCannotShowCachedMacros() {
        val context = instrumentation.targetContext
        val name = "synthetic-read-failure-${UUID.randomUUID()}.db"
        SqliteMealRepository(context, name).use { it.readSelection() }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE meal_selection") }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                    .findViewWithTag<View>("tab:/bolus").performClick() }
                awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_failed, MealFailure.READ_FAILED.code))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "selection:carbs"))
                    assertFalse(view(it, "blocked:calculate").isEnabled)
                    assertFalse(view(it, "blocked:confirm").isEnabled)
                    view(it, "selection:retry").performClick()
                }
                awaitView(scenario, "selection:retry")
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun closingModelDuringSaveDoesNotScheduleAReadOnClosedStorage() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val repository = object : MealRepository, MealSelectionRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(emptyList())
            override fun save(editor: MealRecord): MealSave {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                saved.set(true)
                return MealSave.Saved(editor.copy(revision = 1))
            }
            override fun readSelection(): MealSelection {
                reads.incrementAndGet()
                return MealSelection.Missing
            }
            override fun select(record: MealRecord) = MealSelection.Reviewed(record, record)
        }
        lateinit var model: MealDraftModel
        instrumentation.runOnMainSync {
            model = MealDraftModel(MealDrafts(repository, MealIds { "closing-synthetic" }),
                Closeable { closed.countDown() }, null, ReviewMealSelection(repository))
            model.new(MealKind.DRAFT)
            model.save()
        }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.dispose() }
        } finally { release.countDown() }
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertTrue(saved.get())
        assertEquals(0, reads.get())
    }

    @Test fun editorSavesAndRecoversSyntheticDraftWithoutEnablingClinicalUse() {
        val context = instrumentation.targetContext
        val databaseName = "synthetic-ui-${UUID.randomUUID()}.db"
        val repository = SqliteMealRepository(context, databaseName)
        var model: MealDraftModel? = null
        try {
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    val testModel = MealDraftModel(MealDrafts(repository, MealIds { "synthetic-ui-draft" }),
                        Closeable {}, null)
                    model = testModel
                    lateinit var screen: MealDraftScreen
                    screen = MealDraftScreen(activity, activity.findViewById(R.id.screen_content), testModel) {}
                    testModel.changed = { screen.render(MealKind.DRAFT) }
                    screen.render(MealKind.DRAFT)
                    assertTrue(view(activity, "meal:new").performClick())
                    (view(activity, "meal:name") as EditText).append("Comida sintética")
                    (view(activity, "meal:carbs") as EditText).setText("0")
                    view(activity, "meal:basis").performClick()
                    assertTrue((view(activity, "meal:basis") as android.widget.CheckBox).isChecked)
                    assertFalse(view(activity, "meal:save").tag.toString().startsWith("blocked:"))
                    assertTrue(view(activity, "meal:save").performClick())
                }
                awaitStatus(scenario, context.getString(R.string.draft_saved))
                scenario.onActivity { activity ->
                    assertEquals("meal.draft.not_clinically_validated",
                        (view(activity, "meal:block_code") as TextView).text.toString())
                    assertNull(viewOrNull(activity, "blocked:calculate"))
                    assertNull(viewOrNull(activity, "blocked:confirm"))
                    assertFalse(view(activity, "meal:save").isEnabled)
                    (view(activity, "meal:notes") as EditText).append("revisión visible")
                    assertTrue(view(activity, "meal:save").isEnabled)
                    assertTrue(view(activity, "meal:save").performClick())
                }
                awaitStatus(scenario, context.getString(R.string.draft_saved))
            }
            repository.close()
            SqliteMealRepository(context, databaseName).use { reopened ->
                val record = (reopened.read(MealKind.DRAFT) as MealRead.Loaded).records.single()
                assertEquals(DraftField.Entered("0"), record.content.carbs)
                assertEquals(DraftField.Missing, record.content.fat)
                assertEquals(2, record.revision)
                assertFalse(record.allowsCalculation)
                assertFalse(record.allowsTreatment)
            }
        } finally {
            model?.dispose()
            repository.close()
            assertTrue(context.deleteDatabase(databaseName))
        }
    }

    @Test fun failedPersistenceNeverShowsSavedAndKeepsFieldsForRetry() {
        val context = instrumentation.targetContext
        val failing = object : MealRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(emptyList())
            override fun save(editor: MealRecord) = MealSave.Failed(MealFailure.SAVE_FAILED)
        }
        lateinit var model: MealDraftModel
        launch().use { scenario ->
            scenario.onActivity { activity ->
                model = MealDraftModel(MealDrafts(failing, MealIds { "failed-synthetic" }), Closeable {}, null)
                lateinit var screen: MealDraftScreen
                screen = MealDraftScreen(activity, activity.findViewById(R.id.screen_content), model) {}
                model.changed = { screen.render(MealKind.DRAFT) }
                screen.render(MealKind.DRAFT)
                view(activity, "meal:new").performClick()
                (view(activity, "meal:name") as EditText).append("No persistida")
                (view(activity, "meal:save") as Button).performClick()
            }
            awaitStatus(scenario, context.getString(R.string.draft_save_failed, MealFailure.SAVE_FAILED.code))
            scenario.onActivity { activity ->
                assertEquals("No persistida", (view(activity, "meal:name") as EditText).text.toString())
                assertNotEquals(context.getString(R.string.draft_saved),
                    (view(activity, "meal:status") as TextView).text.toString())
            }
        }
        model.dispose()
    }
}
