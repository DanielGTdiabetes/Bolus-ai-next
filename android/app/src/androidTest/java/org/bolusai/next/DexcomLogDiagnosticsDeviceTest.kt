package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.ObservationFailure
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservationSummary
import org.bolusai.dexcomg7.ObservationSummaryRead
import org.bolusai.dexcomg7.StoredMessage
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.dexcomg7.StoredReading
import org.bolusai.dexcomg7.TransmitterFingerprint
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR 0021: Diagnóstico shows a folded, read-only summary of the Dexcom observation log with counts only. Synthetic
 * readers and in-memory profile and meal repositories; no database of the app is opened.
 */
@RunWith(AndroidJUnit4::class)
class DexcomLogDiagnosticsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reads = AtomicInteger()
    @Volatile private var answer: ObservationSummaryRead = ObservationSummaryRead.Loaded(ObservationSummary.of(ObservationRead.Loaded(emptyList(), emptyList())))

    private val sender = AuthenticatedSender("example.sender", 42, setOf("a".repeat(64)))
    private val loaded = ObservationSummaryRead.Loaded(ObservationSummary.of(ObservationRead.Loaded(
        listOf(StoredMessage("m1", 1_790_000_000_000, sender, StoredMessageOutcome.OBSERVED, null, 1, 0, 0, 0,
            emptyList(), emptyList(), 0)),
        listOf(StoredReading("o1", "m1", 0, TransmitterFingerprint("c".repeat(64)), 10, 187, "SYNTHETIC_TREND", 5, null)),
    )))

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = { SqliteClinicalProfileRepository(it, null) }
        MainActivity.dexcomLogReader = { reads.incrementAndGet(); answer }
    }

    @After fun clear() {
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
        MainActivity.dexcomLogReader = null
    }

    private fun viewOrNull(activity: MainActivity, tag: String): View? =
        activity.findViewById<View>(R.id.app_shell).findViewWithTag(tag)

    private fun await(scenario: ActivityScenario<MainActivity>, what: String, check: (MainActivity) -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < limit) {
            var done = false
            scenario.onActivity { done = check(it) }
            if (done) return
            SystemClock.sleep(25)
        }
        fail("Missing synthetic UI state: $what")
    }

    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) {
        await(scenario, tag) { viewOrNull(it, tag) != null }
        scenario.onActivity { requireNotNull(viewOrNull(it, tag)).performClick() }
    }

    private fun lines(activity: MainActivity): TextView = viewOrNull(activity, "dexcom_log_lines") as TextView

    private fun openDiagnostics(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "tab:/menu")
        click(scenario, "link:native/diagnostics")
        await(scenario, "Diagnóstico") { viewOrNull(it, "dexcom_log_lines") != null }
    }

    @Test fun showsFoldedCountsWithoutAnyGlucoseValue() {
        answer = loaded
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDiagnostics(scenario)
            await(scenario, "loaded") { lines(it).text.contains("dexcom_log.messages=1") }
            scenario.onActivity { activity ->
                assertFalse("Folded by default", lines(activity).isVisible)
                val text = lines(activity).text.toString()
                assertTrue(text.startsWith("dexcom_receiver.component=disabled_by_default\ndexcom_receiver.policy=pending\n"))
                assertTrue(text.contains("dexcom_log.status=dexcom_g7.reading.not_approved_for_use"))
                assertTrue(text.contains("dexcom_log.readings_stored=1"))
                assertTrue(text.contains("dexcom_log.last_sender=example.sender@42"))
                assertFalse(text.contains("187"))
                assertFalse(text.contains("SYNTHETIC_TREND"))
                assertTrue(viewOrNull(activity, "blocking_codes") != null)
            }
            click(scenario, "dexcom_log_toggle")
            scenario.onActivity { assertTrue(lines(it).isVisible) }
        }
    }

    @Test fun aFailedReadShowsItsCode() {
        answer = ObservationSummaryRead.Failed(ObservationFailure.UNSUPPORTED_SCHEMA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDiagnostics(scenario)
            await(scenario, "failed") {
                lines(it).text.toString() == "dexcom_receiver.component=disabled_by_default\n" +
                    "dexcom_receiver.policy=pending\ndexcom_observation.unsupported_schema"
            }
        }
    }

    @Test fun onlyDiagnosticsReadsTheLogAndEachEntryReadsAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click(scenario, "tab:/bolus")
            await(scenario, "Bolo") { viewOrNull(it, "blocked:calculate") != null }
            assertEquals(0, reads.get())
            openDiagnostics(scenario)
            await(scenario, "first read") { reads.get() == 1 && lines(it).text.contains("dexcom_log.messages=0") }
            scenario.onActivity { assertTrue(viewOrNull(it, "dexcom_log_toggle") != null) }
            click(scenario, "tab:/bolus")
            await(scenario, "Bolo again") { viewOrNull(it, "dexcom_log_lines") == null }
            answer = loaded
            openDiagnostics(scenario)
            await(scenario, "second read") { reads.get() == 2 && lines(it).text.contains("dexcom_log.messages=1") }
        }
    }
}
