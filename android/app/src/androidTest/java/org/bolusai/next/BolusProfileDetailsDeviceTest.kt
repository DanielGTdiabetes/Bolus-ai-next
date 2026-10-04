package org.bolusai.next

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.AndroidTimeZoneRules
import org.bolusai.next.profile.ControlledProfileRepository
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.bolusai.next.ui.BlockingDetails
import org.bolusai.next.ui.ClinicalProfileModel
import org.bolusai.next.ui.ClinicalProfileModel.Decision
import org.bolusai.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADR 0017: «Detalles del bloqueo» of Bolo and Diagnóstico show one v2 report with the real profile causes. Synthetic,
 * isolated database files only; the app's own clinical-profile.db is never opened and nothing touches the network.
 * Opening these screens writes nothing, only they start profile reads, and calculation and treatment stay blocked.
 */
@RunWith(AndroidJUnit4::class)
class BolusProfileDetailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "synthetic-bolo-profile-details-${UUID.randomUUID()}.db"
    private val lifted = listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing")
    private val policy = "input.profile.policy_not_approved [profile.not_approved_for_calculation]"
    private val pending = "input.profile.unknown [profile.read.pending]"
    private val missing = "input.profile.unconfirmed [profile.confirmation.missing]"
    private val rejected = "profile_unavailability.unreadable_reason_not_supported"

    @Volatile private var retired = emptySet<String>()
    private val zones = TimeZoneRules { it !in retired && AndroidTimeZoneRules.exists(it) }
    private val ids = OperationIds { OperationId(UUID.randomUUID().toString()) }
    private val repositories = CopyOnWriteArrayList<ControlledProfileRepository>()
    @Volatile private var configure: (ControlledProfileRepository) -> Unit = {}

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = { controlled() }
        MainActivity.profileClock = ProfileClock { 1_790_000_000_000 }
        MainActivity.profileTimeZoneRules = zones
        MainActivity.profileOperationIds = ids
    }

    @After fun clear() {
        repositories.forEach { it.holdBeforeRead?.countDown() }
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
        MainActivity.profileClock = null
        MainActivity.profileTimeZoneRules = null
        MainActivity.profileOperationIds = null
        context.deleteDatabase(name)
    }

    private fun controlled() =
        ControlledProfileRepository(SqliteClinicalProfileRepository(context, name)).also { configure(it); repositories.add(it) }

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun content(sensitivity: ProfileValue = entered("40")) = ProfileContent(1, Setting.Declared(GlucoseUnit.MG_DL),
        Setting.Declared(ProfileTimeZone("Europe/Madrid")), listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, entered("10")),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110"))))

    private fun save(base: Long, body: ProfileContent) = SqliteClinicalProfileRepository(context, name).use {
        (it.save(ProfileWrite(base, body, ProfileOrigin.MANUAL, null), 1_780_000_000_000, "test/seed") as ProfileSave.Saved).version
    }

    private fun <T> useCases(run: (ClinicalProfiles) -> T): T = SqliteClinicalProfileRepository(context, name).use {
        run(ClinicalProfiles(it, ProfileClock { 1_780_000_000_000 }, "test/seed", zones))
    }

    private fun confirm() = useCases { profiles ->
        val state = profiles.readState() as ProfileGateState.Evaluated
        profiles.record(profiles.confirmRequest(state, OperationId(UUID.randomUUID().toString())))
    }

    private fun revoke() = useCases { profiles ->
        val state = profiles.readState() as ProfileGateState.Evaluated
        profiles.record(profiles.revokeRequest(state, OperationId(UUID.randomUUID().toString()))!!)
    }

    /** Raw rows of the synthetic file: versions, segments and confirmation events. Never the app's own database. */
    private fun rows(): List<Long> {
        val path = context.getDatabasePath(name)
        if (!path.exists()) return listOf(0, 0, 0)
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("profile_versions", "profile_segments", "profile_confirmation_events").map { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
            }
        }
    }

    private fun writes() = repositories.sumOf { it.saves.get() + it.requests.size }
    private fun reads() = repositories.sumOf { it.reads.get() }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
        scenario.onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
        }
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
        scenario.onActivity { activity -> requireNotNull(viewOrNull(activity, tag)).performClick() }
    }

    private fun openBolus(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "tab:/bolus")
        await(scenario, "Bolo") { viewOrNull(it, "blocked:calculate") != null }
    }

    private fun openDiagnostics(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "tab:/menu")
        click(scenario, "link:native/diagnostics")
        await(scenario, "Diagnóstico") { viewOrNull(it, "blocking_codes") != null && viewOrNull(it, "blocked:calculate") == null }
    }

    private fun openProfile(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "tab:/menu")
        click(scenario, "link:/settings")
        click(scenario, "settings:CALCULATION")
        await(scenario, "profile:safety") { viewOrNull(it, "profile:safety") != null }
    }

    private fun codes(activity: MainActivity) = viewOrNull(activity, "blocking_codes") as TextView
    private fun lines(activity: MainActivity) = codes(activity).text.toString().split('\n')

    /** Waits until the block shows exactly the lifted inputs plus [profile], in report order. */
    private fun shows(scenario: ActivityScenario<MainActivity>, vararg profile: String) {
        val expected = (lifted + profile).sortedBy { it.substringBefore(' ') }
        await(scenario, expected.joinToString(" | ")) { activity ->
            (viewOrNull(activity, "blocking_codes") as? TextView)?.text?.toString()?.split('\n') == expected
        }
        scenario.onActivity { activity ->
            assertEquals(expected, lines(activity))
            assertBlocked(activity)
        }
    }

    /** Calculation and treatment stay blocked whatever the profile state, also with a confirmed profile. */
    private fun assertBlocked(activity: MainActivity) {
        viewOrNull(activity, "blocked:calculate")?.let { assertFalse(it.isEnabled) }
        viewOrNull(activity, "blocked:confirm")?.let { assertFalse(it.isEnabled) }
        assertNull(viewOrNull(activity, "profile:retry"))
        assertNull(viewOrNull(activity, "profile:unavailability"))
    }

    @Test fun destinationsWithoutTheBlockStartNoProfileRead() {
        save(0, content())
        launch().use { scenario ->
            click(scenario, "tab:/menu")
            click(scenario, "tab:/scan")
            click(scenario, "tab:/notifications")
            click(scenario, "tab:/")
            SystemClock.sleep(300)
            assertEquals(0, reads())
            openBolus(scenario)
            shows(scenario, policy, missing)
            assertEquals(1, reads())
        }
    }

    @Test fun boloShowsThePendingReadAndThenExactlyTheReadStateWithoutWrites() {
        save(0, content())
        val before = rows()
        val hold = CountDownLatch(1)
        configure = { it.holdBeforeRead = hold }
        launch().use { scenario ->
            openBolus(scenario)
            assertTrue(repositories.last().readStarted.await(8, TimeUnit.SECONDS))
            shows(scenario, policy, pending)
            scenario.onActivity { assertFalse("Folded by default", codes(it).isVisible) }
            click(scenario, "details")
            scenario.onActivity { assertTrue(codes(it).isVisible) }
            hold.countDown()
            shows(scenario, policy, missing)
            scenario.onActivity { activity ->
                assertFalse(lines(activity).contains(pending))
                // The block stays open while the same destination repaints with the new state (B7).
                assertTrue(codes(activity).isVisible)
            }
            // Leaving and coming back folds it and reads nothing again (B6).
            click(scenario, "tab:/")
            openBolus(scenario)
            scenario.onActivity { assertFalse(codes(it).isVisible) }
            shows(scenario, policy, missing)
            // Recreation keeps the model state: folded, same codes, no new read (B8).
            scenario.recreate()
            await(scenario, "Bolo after recreation") { viewOrNull(it, "blocking_codes") != null }
            scenario.onActivity { assertFalse(codes(it).isVisible) }
            shows(scenario, policy, missing)
            assertEquals(1, reads())
        }
        assertEquals(0, writes())
        assertEquals(before, rows())
    }

    @Test fun everyProvokedStateShowsExactCodesInBoloAndDiagnostics() {
        launch().use { scenario -> openBolus(scenario); shows(scenario, "input.profile.missing [profile.history.missing]", policy) }
        save(0, content(sensitivity = ProfileValue.NotConfigured))
        launch().use { scenario ->
            openDiagnostics(scenario)
            shows(scenario, "input.profile.incomplete [profile.gate.incomplete]", policy, missing)
        }
        save(1, content())
        confirm()
        launch().use { scenario ->
            openBolus(scenario)
            // Confirmed data still carry the policy cause and keep calculation and treatment blocked.
            shows(scenario, policy)
            openDiagnostics(scenario)
            shows(scenario, policy)
        }
        revoke()
        launch().use { scenario ->
            openBolus(scenario)
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.revoked]")
        }
        confirm()
        retired = setOf("Europe/Madrid")
        launch().use { scenario ->
            openDiagnostics(scenario)
            shows(scenario, "input.profile.invalid [profile.gate.time_zone_unrecognized]", policy)
        }
        retired = emptySet()
        save(2, content(sensitivity = entered("45")))
        launch().use { scenario ->
            openBolus(scenario)
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.missing, profile.confirmation.superseded]")
        }
        assertEquals(0, writes())
    }

    @Test fun aFailedReadIsNotRetriedInBoloAndARetryInSettingsReplacesIt() {
        save(0, content())
        configure = { it.readFailure = ProfileFailure.READ_FAILED }
        launch().use { scenario ->
            val failed = "input.profile.persistence_failed [profile.storage.read_failed]"
            openBolus(scenario)
            shows(scenario, failed, policy)
            openDiagnostics(scenario)
            shows(scenario, failed, policy)
            openBolus(scenario)
            shows(scenario, failed, policy)
            assertEquals("Bolo and Diagnóstico never retry (B6)", 1, reads())
            repositories.last().readFailure = null
            openProfile(scenario)
            click(scenario, "profile:retry")
            await(scenario, "profile read again") { viewOrNull(it, "profile:retry") == null && viewOrNull(it, "profile:review:open") != null }
            openBolus(scenario)
            shows(scenario, policy, missing)
            scenario.onActivity { assertFalse(lines(it).contains(failed)) }
        }
        assertEquals(0, writes())
    }

    @Test fun theKnownRejectionShowsItsIdentifierWithoutAProfileCause() {
        save(0, content())
        configure = { it.readFailure = ProfileFailure.SAVE_FAILED }
        launch().use { scenario ->
            openBolus(scenario)
            val expected = lifted + rejected
            await(scenario, "rejection") { (viewOrNull(it, "blocking_codes") as? TextView)?.text?.toString()?.split('\n') == expected }
            scenario.onActivity { activity ->
                assertTrue(lines(activity).none { it.startsWith("input.profile.") })
                assertBlocked(activity)
            }
        }
        assertEquals(0, writes())
    }

    @Test fun openingBoloAndDiagnosticsWithAPendingOperationWritesNothing() {
        save(0, content())
        val before = rows()
        configure = { it.writeFailure = ProfileFailure.SAVE_FAILED }
        launch().use { scenario ->
            openProfile(scenario)
            click(scenario, "profile:review:open")
            click(scenario, "profile:review:confirm")
            await(scenario, "failed attempt kept pending") { viewOrNull(it, "profile:confirmation:notice") != null && viewOrNull(it, "profile:review:recording") == null }
            val attempts = writes()
            assertEquals(1, attempts)
            openBolus(scenario)
            shows(scenario, policy, missing)
            openDiagnostics(scenario)
            shows(scenario, policy, missing)
            scenario.recreate()
            await(scenario, "Diagnóstico after recreation") { viewOrNull(it, "blocking_codes") != null }
            shows(scenario, policy, missing)
            openBolus(scenario)
            shows(scenario, policy, missing)
            assertEquals("Opening Bolo or Diagnóstico adds no write", attempts, writes())
        }
        assertEquals(before, rows())
    }

    // A new model created from the saved state, as after process death (ADR 0017, section 14).

    private val overview = ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute()

    private fun model(repository: ControlledProfileRepository, state: Bundle?): ClinicalProfileModel {
        lateinit var model: ClinicalProfileModel
        instrumentation.runOnMainSync {
            model = ClinicalProfileModel(ClinicalProfiles(repository, ProfileClock { 1_790_000_000_000 }, "test/details", zones),
                repository, state, ids)
        }
        return model
    }

    private fun details(model: ClinicalProfileModel): List<String> {
        lateinit var shown: List<String>
        instrumentation.runOnMainSync { shown = BlockingDetails.lines(overview, model.unavailability) }
        return shown
    }

    private fun awaitModel(model: ClinicalProfileModel, condition: (ClinicalProfileModel) -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 8_000
        var done = false
        while (!done && SystemClock.uptimeMillis() < limit) {
            instrumentation.runOnMainSync { done = condition(model) }
            if (!done) SystemClock.sleep(20)
        }
        assertTrue("Model condition not reached", done)
    }

    /** Leaves a confirmation whose write failed pending in [model] and returns its saved state. */
    private fun failedConfirmation(model: ClinicalProfileModel): Bundle {
        instrumentation.runOnMainSync { model.ensureLoaded() }
        awaitModel(model) { it.evaluated != null }
        instrumentation.runOnMainSync { model.openReview() }
        awaitModel(model) { (it.decision as? Decision.Review)?.target != null && it.evaluated != null }
        instrumentation.runOnMainSync { model.confirm() }
        awaitModel(model) { it.pending != null && it.recording == null }
        lateinit var state: Bundle
        instrumentation.runOnMainSync { state = model.snapshot() }
        return state
    }

    private fun passesThroughThePendingRead(state: Bundle?) {
        val before = rows()
        val repository = controlled().apply { holdBeforeRead = CountDownLatch(1) }
        val fresh = model(repository, state?.let { Bundle(it) })
        assertEquals((lifted + listOf(policy, pending)).sortedBy { it.substringBefore(' ') }, details(fresh))
        instrumentation.runOnMainSync { fresh.ensureLoaded() }
        assertTrue(repository.readStarted.await(8, TimeUnit.SECONDS))
        assertTrue(pending in details(fresh))
        repository.holdBeforeRead!!.countDown()
        awaitModel(fresh) { it.gate != null }
        assertEquals((lifted + listOf(policy, missing)).sortedBy { it.substringBefore(' ') }, details(fresh))
        assertEquals(0, repository.saves.get())
        assertTrue(repository.requests.isEmpty())
        assertEquals(before, rows())
        instrumentation.runOnMainSync { assertFalse(fresh.gate!!.allowsCalculation || fresh.gate!!.allowsTreatment) }
    }

    @Test fun aNewModelAfterProcessDeathPassesThroughE1BeforeTheResultWithoutWrites() {
        save(0, content())
        passesThroughThePendingRead(null)
    }

    @Test fun aNewModelKeepingAPendingOperationResolvesItReadOnlyThroughE1() {
        save(0, content())
        val first = controlled().apply { writeFailure = ProfileFailure.SAVE_FAILED }
        val state = failedConfirmation(model(first, null))
        assertNotNull(state.getString("pendingId"))
        passesThroughThePendingRead(state)
    }
}
