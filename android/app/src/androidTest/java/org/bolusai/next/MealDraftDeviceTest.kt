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
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.UUID

/** Uses isolated synthetic repositories; it never reads or deletes the app's normal meal database. */
@RunWith(AndroidJUnit4::class)
class MealDraftDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun launch() = ActivityScenario.launch(MainActivity::class.java)

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
