package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.DraftField
import org.bolusai.meals.MealKind
import org.bolusai.meals.MealRead
import org.bolusai.meals.draftField
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.mfp.SyntheticMfpDiary
import org.bolusai.next.mfp.VolatileMfpLinkSwitch
import org.bolusai.next.ui.MfpLinkText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ADR 0032, second delivery: the MyFitnessPal screen from switch to draft, on a synthetic page answered by the test.
 * Nothing reaches the network, the app's own switch and session are not touched, and the meal and profile stores are
 * synthetic.
 */
@RunWith(AndroidJUnit4::class)
class MfpLinkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mealsName = "synthetic-mfp-${UUID.randomUUID()}.db"
    private val answers = SyntheticMfpDiary.Answers()

    @Before fun isolate() {
        MainActivity.profileRepositoryFactory = { org.bolusai.next.profile.SqliteClinicalProfileRepository(it, null) }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, mealsName) }
        MainActivity.mfpAnswers = answers
    }

    @After fun clear() {
        MainActivity.profileRepositoryFactory = null
        MainActivity.mealRepositoryFactory = null
        MainActivity.mfpSwitchFactory = null
        MainActivity.mfpAnswers = null
        context.deleteDatabase(mealsName)
    }

    private companion object {
        const val NOT_A_DAY = "no es un día"
    }

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

    private fun viewOrNull(activity: MainActivity, tag: String): View? =
        activity.findViewById<View>(R.id.screen_content).findViewWithTag(tag)
    private fun view(activity: MainActivity, tag: String): View = requireNotNull(viewOrNull(activity, tag)) { tag }
    private fun text(activity: MainActivity, tag: String) = (view(activity, tag) as TextView).text.toString()

    private fun await(scenario: ActivityScenario<MainActivity>, tag: String, timeoutMs: Long = 5_000) {
        val limit = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < limit) {
            var found = false
            scenario.onActivity { found = viewOrNull(it, tag) != null }
            if (found) return
            SystemClock.sleep(50)
        }
        fail("Missing synthetic UI state: $tag")
    }

    private fun openLinkScreen(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/menu").performClick() }
        await(scenario, "link:native/settings")
        scenario.onActivity { view(it, "link:native/settings").performClick() }
        await(scenario, "mfp:status")
    }

    private fun drafts(): List<org.bolusai.meals.MealRecord> = SqliteMealRepository(context, mealsName).use {
        (it.read(MealKind.DRAFT) as MealRead.Loaded).records
    }

    @Test fun linkIsOffByDefaultAndThenNothingCanConnect() {
        // No switch injected: instrumentation gets one that is off and forgets, never the stored one.
        assertFalse(MainActivity.mfpSwitch(context).enabled)
        assertTrue(MainActivity.mfpSwitch(context) is VolatileMfpLinkSwitch)
        launch().use { scenario ->
            openLinkScreen(scenario)
            scenario.onActivity { activity ->
                assertEquals(MfpLinkText.OFF, text(activity, "mfp:status"))
                assertEquals(MfpLinkText.TURN_ON, text(activity, "mfp:toggle"))
                listOf("mfp:web", "mfp:read", "mfp:open", "mfp:date", "mfp:signout").forEach {
                    assertNull(it, viewOrNull(activity, it))
                }
            }
        }
        assertTrue(answers.asked.isEmpty())
    }

    @Test fun readImportsOnlyOnRequestAndReimportWritesNothing() {
        MainActivity.mfpSwitchFactory = { VolatileMfpLinkSwitch() }
        launch().use { scenario ->
            openLinkScreen(scenario)
            scenario.onActivity { view(it, "mfp:toggle").performClick() }
            await(scenario, "mfp:read")
            scenario.onActivity { activity ->
                assertEquals(MfpLinkText.ON, text(activity, "mfp:status"))
                // Switching on connects to nothing and builds no web view.
                assertNull(viewOrNull(activity, "mfp:web"))
                (view(activity, "mfp:date") as EditText).setText(NOT_A_DAY)
                view(activity, "mfp:read").performClick()
            }
            await(scenario, "mfp:notice")
            assertTrue(answers.asked.isEmpty())

            scenario.onActivity { activity ->
                (view(activity, "mfp:date") as EditText).setText(SyntheticMfpDiary.DATE)
                view(activity, "mfp:read").performClick()
            }
            // Two loads separated by the pause observed in Legacy.
            await(scenario, "mfp:meal:0", 30_000)
            assertEquals(2, answers.asked.count { it == SyntheticMfpDiary.URL })
            scenario.onActivity { activity ->
                val meal = text(activity, "mfp:meal:0").lines()
                assertEquals("breakfast · 2 alimentos · mfp:2026-01-15:breakfast", meal[0])
                assertEquals("Hidratos: 36,6 g", meal[1])
                assertEquals("Fibra: sin dato (mfp.value.food_unknown)", meal[4])
                assertNull(viewOrNull(activity, "mfp:meal:1"))
                assertTrue(text(activity, "mfp:blocked").contains("tracker.example.org"))
            }
            // Reading alone stores nothing.
            assertTrue(drafts().isEmpty())

            scenario.onActivity { view(it, "mfp:import:0").performClick() }
            await(scenario, "mfp:outcome:0")
            val draft = drafts().single()
            assertEquals("mfp:2026-01-15:breakfast", draft.id)
            assertEquals(1L, draft.revision)
            assertEquals(draftField("36.6"), draft.content.carbs)
            assertEquals(DraftField.Missing, draft.content.fiber)
            assertTrue((draft.content.notes as DraftField.Entered).text.contains("mfp-revision="))

            scenario.onActivity { view(it, "mfp:import:0").performClick() }
            SystemClock.sleep(500)
            await(scenario, "mfp:outcome:0")
            scenario.onActivity {
                assertEquals("Ya estaba importada esta misma versión; no se ha cambiado nada.", text(it, "mfp:outcome:0"))
            }
            assertEquals(1L, drafts().single().revision)

            // Switching off ends the session view and forgets the reading.
            scenario.onActivity { view(it, "mfp:toggle").performClick() }
            scenario.onActivity { activity ->
                assertEquals(MfpLinkText.OFF, text(activity, "mfp:status"))
                listOf("mfp:web", "mfp:meal:0", "mfp:read").forEach { assertNull(it, viewOrNull(activity, it)) }
            }
        }
    }
}
