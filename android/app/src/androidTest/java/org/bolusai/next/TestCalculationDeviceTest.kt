package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.bolusai.next.ui.DexcomReadingModel
import org.bolusai.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Pixel calculation smoke tests: synthetic inputs and in-memory repositories only, never the user's profile/log. */
@RunWith(AndroidJUnit4::class)
class TestCalculationDeviceTest {
    private val at = 1_791_793_800_000L
    @Volatile private var reading = LatestReadingPresentation.Reading(190, at - 120_000, 120_000,
        ApprovedPermissionSet("synthetic-policy", "c".repeat(64)), "ab12cd34", "SYNTHETIC_TREND")

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = { context ->
            SqliteClinicalProfileRepository(context, null).also { repository ->
                val content = ProfileContent(ProfileCatalog.CURRENT_SCHEMA, Setting.Declared(GlucoseUnit.MG_DL),
                    Setting.Declared(ProfileTimeZone("Europe/Madrid")), listOf(
                        ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ProfileValue.Entered(CanonicalDecimal("10"))),
                        ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, ProfileValue.Entered(CanonicalDecimal("40"))),
                        ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, ProfileValue.Entered(CanonicalDecimal("110")))))
                    .withLimit(ProfileLimit.ROUND_STEP, ProfileValue.Entered(CanonicalDecimal("0.1")))
                    .withLimit(ProfileLimit.MAX_BOLUS, ProfileValue.Entered(CanonicalDecimal("15")))
                    .withLimit(ProfileLimit.MAX_CORRECTION, ProfileValue.Entered(CanonicalDecimal("5")))
                assertTrue(repository.save(ProfileWrite(0, content, ProfileOrigin.MANUAL, null), at - 1_000,
                    "test/synthetic") is ProfileSave.Saved)
                val profiles = ClinicalProfiles(repository, ProfileClock { at }, "test/synthetic", TimeZoneRules { true })
                val state = profiles.readState() as ProfileGateState.Evaluated
                assertTrue(profiles.record(profiles.confirmRequest(state, OperationId(UUID.randomUUID().toString())))
                    is ConfirmationOutcome.Recorded)
            }
        }
        MainActivity.dexcomPresentationReader = { reading }
        MainActivity.clock = { at }
    }

    @After fun clear() {
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
        MainActivity.dexcomPresentationReader = null
        MainActivity.clock = { System.currentTimeMillis() }
    }

    private fun view(activity: MainActivity, tag: String): View? =
        activity.findViewById<View>(R.id.app_shell).findViewWithTag(tag)

    private fun await(scenario: ActivityScenario<MainActivity>, what: String, check: (MainActivity) -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) {
            var done = false
            scenario.onActivity { done = check(it) }
            if (done) return
            SystemClock.sleep(25)
        }
        fail("Missing synthetic calculation UI state: $what")
    }

    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) {
        await(scenario, tag) { view(it, tag) != null }
        scenario.onActivity { assertTrue(requireNotNull(view(it, tag)).performClick()) }
    }

    private fun enter(scenario: ActivityScenario<MainActivity>, tag: String, text: String) {
        scenario.onActivity { (requireNotNull(view(it, "test_calc:$tag")) as EditText).setText(text) }
    }

    private fun openAndFill(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "tab:/bolus")
        await(scenario, "confirmed synthetic profile") {
            val model = ViewModelProvider(it)[org.bolusai.next.ui.ClinicalProfileModel::class.java]
            model.gate is ProfileGateState.Evaluated
        }
        enter(scenario, "carbs", "60")
        enter(scenario, "glucose", "190")
    }

    @Test fun manualCalculationKeepsUnknownIobBlockedAndDiscardsResultOnEdit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openAndFill(scenario)
            click(scenario, "test_calc:run")
            scenario.onActivity {
                assertEquals(it.getString(R.string.test_calc_total_blocked), (view(it, "test_calc:total") as TextView).text.toString())
            }
            enter(scenario, "iob", "0") // Explicit synthetic zero, never an absent-value fallback.
            click(scenario, "test_calc:run")
            scenario.onActivity {
                assertEquals(it.getString(R.string.test_calc_total, "8"), (view(it, "test_calc:total") as TextView).text.toString())
                assertNotNull(view(it, "test_calc:banner"))
                assertNotNull(view(it, "test_calc:fingerprint"))
                assertFalse(requireNotNull(view(it, "blocked:confirm")).isEnabled)
            }
            enter(scenario, "carbs", "61")
            scenario.onActivity {
                assertNull(view(it, "test_calc:total"))
                assertNotNull(view(it, "test_calc:invalidated"))
            }
        }
    }

    @Test fun aChangedSyntheticDexcomReadingDiscardsTheDisplayedDose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openAndFill(scenario)
            enter(scenario, "iob", "0")
            await(scenario, "synthetic Dexcom reading") {
                ViewModelProvider(it)[DexcomReadingModel::class.java].state is LatestReadingPresentation.Reading
            }
            click(scenario, "test_calc:glucose:dexcom")
            click(scenario, "test_calc:run")
            scenario.onActivity {
                assertEquals(it.getString(R.string.test_calc_total, "8"), (view(it, "test_calc:total") as TextView).text.toString())
            }
            reading = reading.copy(valueMgDl = 191, receivedAtEpochMs = at - 60_000)
            scenario.onActivity { ViewModelProvider(it)[DexcomReadingModel::class.java].load() }
            await(scenario, "old result discarded") { view(it, "test_calc:invalidated") != null }
            scenario.onActivity { assertNull(view(it, "test_calc:total")) }
        }
    }
}
