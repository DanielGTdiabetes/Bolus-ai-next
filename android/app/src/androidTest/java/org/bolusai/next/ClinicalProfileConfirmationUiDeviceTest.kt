package org.bolusai.next

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.AndroidTimeZoneRules
import org.bolusai.next.profile.ControlledProfileRepository
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.bolusai.next.ui.ClinicalProfileModel
import org.bolusai.next.ui.ClinicalProfileModel.ConfirmationNotice
import org.bolusai.next.ui.ClinicalProfileModel.Decision
import org.bolusai.next.ui.ClinicalProfileScreen
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
 * ADR 0014 part 2: review, confirmation and revocation of the saved profile data on screen (section 9) and the UI
 * tests of section 10. Synthetic, isolated database files only; the app's own clinical-profile.db is never opened,
 * nothing touches the network and calculation stays blocked in every state.
 */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileConfirmationUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "synthetic-profile-confirmation-ui-${UUID.randomUUID()}.db"
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))
    private val ratio = ProfileParameter.CARB_RATIO
    private val isf = ProfileParameter.INSULIN_SENSITIVITY
    private val target = ProfileParameter.GLUCOSE_TARGET

    /** Zones the synthetic platform no longer recognizes (E11). */
    @Volatile private var retired = emptySet<String>()
    private val zones = TimeZoneRules { it !in retired && AndroidTimeZoneRules.exists(it) }
    private val issued = CopyOnWriteArrayList<OperationId>()
    private val ids = OperationIds { OperationId(UUID.randomUUID().toString()).also { issued.add(it) } }
    private val repositories = CopyOnWriteArrayList<ControlledProfileRepository>()

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = { controlled() }
        MainActivity.profileClock = ProfileClock { 1_790_000_000_000 }
        MainActivity.profileTimeZoneRules = zones
        MainActivity.profileOperationIds = ids
    }

    @After fun clear() {
        repositories.forEach { repository ->
            repository.holdBeforeRead?.countDown()
            repository.holdBeforeWrite?.countDown()
            repository.holdAfterCommit?.countDown()
        }
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
        MainActivity.profileClock = null
        MainActivity.profileTimeZoneRules = null
        MainActivity.profileOperationIds = null
        context.deleteDatabase(name)
    }

    private fun controlled() = ControlledProfileRepository(SqliteClinicalProfileRepository(context, name)).also { repositories.add(it) }
    private fun screenRepository() = repositories.last()

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun seg(start: Int, end: Int, value: ProfileValue) = TimeSegment(start, end, value)
    private fun content(ratioValue: ProfileValue = entered("10"), sensitivity: ProfileValue = entered("40"),
                        targetValue: ProfileValue = entered("110")) =
        ProfileContent(1, mgdl, madrid, listOf(
            ParameterSchedule.allDay(ratio, ratioValue),
            ParameterSchedule.allDay(isf, sensitivity),
            ParameterSchedule.allDay(target, targetValue)))

    private fun <T> useCases(run: (ClinicalProfiles) -> T): T = SqliteClinicalProfileRepository(context, name).use {
        run(ClinicalProfiles(it, ProfileClock { 1_780_000_000_000 }, "test/seed", zones))
    }

    private fun save(base: Long, body: ProfileContent): ProfileVersion = SqliteClinicalProfileRepository(context, name).use {
        (it.save(ProfileWrite(base, body, ProfileOrigin.MANUAL, null), 1_780_000_000_000, "test/seed") as ProfileSave.Saved).version
    }

    private fun externalConfirm(): ConfirmationEvent = useCases { profiles ->
        val state = profiles.readState() as ProfileGateState.Evaluated
        (profiles.record(profiles.confirmRequest(state, OperationId(UUID.randomUUID().toString()))) as ConfirmationOutcome.Recorded).event
    }

    private fun externalRevoke(): ConfirmationEvent = useCases { profiles ->
        val state = profiles.readState() as ProfileGateState.Evaluated
        (profiles.record(profiles.revokeRequest(state, OperationId(UUID.randomUUID().toString()))!!) as ConfirmationOutcome.Recorded).event
    }

    private fun events(): List<ConfirmationEvent> = SqliteClinicalProfileRepository(context, name).use {
        (it.readRecord() as ProfileRecordRead.Loaded).record.events
    }

    private fun rawEventCount(): Long = android.database.sqlite.SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
        android.database.DatabaseUtils.longForQuery(it, "SELECT count(*) FROM profile_confirmation_events", null)
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
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
    private fun type(activity: MainActivity, tag: String, value: String) = (view(activity, tag) as EditText).setText(value)
    /** Waits for asynchronous reads to render the control before clicking it. */
    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) {
        awaitView(scenario, tag)
        scenario.onActivity {
            val target = view(it, tag)
            assertTrue("Disabled: $tag", target.isEnabled)
            target.performClick()
        }
    }

    private fun awaitView(scenario: ActivityScenario<MainActivity>, tag: String, expected: String? = null) {
        val limit = SystemClock.uptimeMillis() + 8_000
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

    private fun openProfile(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/menu").performClick() }
        click(scenario, "link:/settings")
        click(scenario, "settings:CALCULATION")
        awaitView(scenario, "profile:safety")
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun texts(root: View): List<String> = descendants(root).filterIsInstance<TextView>().filter { it.isVisible }
        .map { it.text.toString() }

    private fun descendants(root: View): List<View> = listOf(root) + if (root is android.view.ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    /** ADR 0014, section 9.1: confirmed data never appears without the calculation block, and nothing claims approval. */
    private fun assertNeverApproved(activity: MainActivity) {
        val all = texts(activity.findViewById(R.id.screen_content))
        all.filter { it.contains("Datos confirmados") }.forEach {
            assertTrue("Without block: $it", it.contains("Cálculo todavía bloqueado"))
        }
        listOf("perfil válido", "perfil aprobado", "apto para dosificar", "listo para calcular", "perfil activo",
            "Guardar y confirmar").forEach { forbidden ->
            assertFalse(forbidden, all.any { it.contains(forbidden, ignoreCase = true) })
        }
        assertEquals(ProfileVersion.NOT_APPROVED_CODE, text(activity, "profile:block"))
    }

    private fun gateIs(scenario: ActivityScenario<MainActivity>, expected: String) {
        awaitView(scenario, "profile:gate", expected)
        scenario.onActivity { assertNeverApproved(it) }
    }

    // States E5 to E11.

    @Test fun everyStateHasItsTextAndConfirmedDataAlwaysComesWithTheCalculationBlock() {
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:status", s(R.string.profile_missing))
            scenario.onActivity {
                assertNull(viewOrNull(it, "profile:review:open"))
                assertNull(viewOrNull(it, "profile:revoke:open"))
                assertNeverApproved(it)
            }
        }
        // E6: every missing value is listed and nothing can be confirmed.
        save(0, content(sensitivity = ProfileValue.NotConfigured).copy(schedules = listOf(
            ParameterSchedule.allDay(ratio, entered("10")),
            ParameterSchedule(isf, listOf(seg(0, 360, entered("40")), seg(360, 720, ProfileValue.NotConfigured),
                seg(720, 1440, entered("45")))),
            ParameterSchedule.allDay(target, ProfileValue.NotConfigured))))
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_incomplete, 1))
            scenario.onActivity {
                assertEquals(s(R.string.profile_gaps_title), text(it, "profile:gate:gaps"))
                assertEquals(s(R.string.profile_gap_value, s(R.string.profile_param_insulin_sensitivity), "06:00–12:00"),
                    text(it, "profile:gate:gaps:0"))
                assertEquals(s(R.string.profile_gap_value, s(R.string.profile_param_glucose_target), "00:00–24:00"),
                    text(it, "profile:gate:gaps:1"))
                assertNull(viewOrNull(it, "profile:review:open"))
            }
        }
        // E7, then E9, E10 and E8 through the same screen after each external fact.
        save(1, content())
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_unconfirmed, 2))
            scenario.onActivity { assertTrue(view(it, "profile:review:open").isEnabled) }
        }
        externalConfirm()
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_confirmed, 2))
            scenario.onActivity {
                assertNull(viewOrNull(it, "profile:review:open"))
                assertTrue(view(it, "profile:revoke:open").isEnabled)
            }
        }
        externalRevoke()
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_revoked, 2))
            scenario.onActivity { assertTrue(view(it, "profile:review:open").isEnabled) }
        }
        externalConfirm()
        save(2, content(ratioValue = entered("12")))
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_superseded, 3, 2))
        }
    }

    @Test fun retiredTimeZoneKeepsTheConfirmationAllowsRevokingAndThenBlocksConfirming() {
        save(0, content())
        externalConfirm()
        retired = setOf("Europe/Madrid")
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_confirmed_zone, 1, "Europe/Madrid"))
            scenario.onActivity { assertNull(viewOrNull(it, "profile:review:open")) }
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:question", s(R.string.profile_revoke_question, 1))
            click(scenario, "profile:revoke:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_revoked, 1))
            gateIs(scenario, s(R.string.profile_gate_revoked, 1))
            scenario.onActivity {
                assertEquals(s(R.string.profile_gap_zone_unrecognized, "Europe/Madrid"), text(it, "profile:gate:gaps:0"))
                // Revoked and incomplete on this device: confirming again needs a recognized zone first.
                assertNull(viewOrNull(it, "profile:review:open"))
                assertNull(viewOrNull(it, "profile:revoke:open"))
            }
        }
        assertEquals(listOf(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.REVOKE), events().map { it.kind })
    }

    // Review from a fresh read.

    @Test fun reviewIsBuiltFromAFreshReadShowsEverySegmentAndHighlightsExplicitZeros() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            gateIs(scenario, s(R.string.profile_gate_unconfirmed, 1))
            // The screen still shows version 1 while another writer saves version 2 and adds events.
            val v2 = save(1, ProfileContent(1, mgdl, madrid, listOf(
                ParameterSchedule(ratio, listOf(seg(0, 360, entered("12")), seg(360, 1440, entered("0")))),
                ParameterSchedule.allDay(isf, entered("0")),
                ParameterSchedule(target, listOf(seg(0, 600, entered("110")), seg(600, 1440, entered("110")))))))
            externalConfirm()
            externalRevoke()
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:title", s(R.string.profile_review_title, 2))
            scenario.onActivity {
                assertEquals(s(R.string.profile_review_explanation), text(it, "profile:review:explanation"))
                assertTrue(text(it, "profile:review:identity").startsWith("Versión 2 · guardada el "))
                assertTrue(text(it, "profile:review:identity").endsWith(
                    "· huella ${v2.contentSha256.take(4)}…${v2.contentSha256.takeLast(4)}"))
                assertEquals(s(R.string.profile_unit_value, "mg/dL"), text(it, "profile:review:unit"))
                assertEquals(s(R.string.profile_zone_value, "Europe/Madrid"), text(it, "profile:review:zone"))
                assertEquals(s(R.string.profile_param_with_unit, s(R.string.profile_param_insulin_sensitivity), "mg/dL/U"),
                    text(it, "profile:review:param:insulin_sensitivity"))
                assertEquals("00:00–06:00 · 12", text(it, "profile:review:segment:carb_ratio@0-360"))
                assertEquals(s(R.string.profile_review_zero, "06:00", "24:00"), text(it, "profile:review:segment:carb_ratio@360-1440"))
                assertEquals(s(R.string.profile_review_zero, "00:00", "24:00"),
                    text(it, "profile:review:segment:insulin_sensitivity@0-1440"))
                // Adjacent equal segments are shown as stored, never summarized.
                assertEquals("00:00–10:00 · 110", text(it, "profile:review:segment:glucose_target@0-600"))
                assertEquals("10:00–24:00 · 110", text(it, "profile:review:segment:glucose_target@600-1440"))
                // Read-only: nothing editable, no save and no editor actions.
                assertTrue(descendants(it.findViewById(R.id.screen_content)).none { v -> v is EditText })
                assertNull(viewOrNull(it, "profile:save"))
                assertNull(viewOrNull(it, "profile:edit"))
                assertNeverApproved(it)
            }
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 2))
            gateIs(scenario, s(R.string.profile_gate_confirmed, 2))
            val confirmation = events().last()
            assertEquals(2L, confirmation.profileVersion)
            assertEquals(v2.contentSha256, confirmation.contentSha256)
            assertEquals(2L, confirmation.observedEventSeq)
            assertEquals(listOf(confirmation.operationId), issued)
        }
    }

    @Test fun reviewIsBlockedByUnsavedChangesPendingTextOpenPanelOrPendingUnitChange() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:edit")
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:save")
            // The editor itself never offers confirming.
            scenario.onActivity { assertNull(viewOrNull(it, "profile:review:open")) }
            fun assertBlocked(change: (MainActivity) -> Unit, undo: (MainActivity) -> Unit) {
                scenario.onActivity(change)
                click(scenario, "profile:history")
                awaitView(scenario, "profile:review:blocked", s(R.string.profile_review_blocked))
                scenario.onActivity { assertFalse(view(it, "profile:review:open").isEnabled) }
                click(scenario, "profile:history:close")
                awaitView(scenario, "profile:save")
                scenario.onActivity(undo)
            }
            val value = "profile:value:carb_ratio@0-1440"
            assertBlocked({ type(it, value, "11") }, { type(it, value, "10") })
            assertBlocked({ type(it, value, "1.000") }, { type(it, value, "10") })
            assertBlocked({ view(it, "profile:split:carb_ratio@0-1440").performClick() },
                { view(it, "profile:panel:cancel").performClick() })
            assertBlocked({ view(it, "profile:unit:mmol_l").performClick() }, { view(it, "profile:unit:mg_dl").performClick() })
        }
        assertTrue(events().isEmpty())
        // An editor without changes holds nothing to lose: the review opens from the saved version.
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:edit")
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:history")
            click(scenario, "profile:history")
            awaitView(scenario, "profile:review:open")
            scenario.onActivity { assertNull(viewOrNull(it, "profile:review:blocked")) }
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:title", s(R.string.profile_review_title, 1))
            scenario.onActivity { assertNull(viewOrNull(it, "profile:save")) }
            click(scenario, "profile:review:back")
            gateIs(scenario, s(R.string.profile_gate_unconfirmed, 1))
        }
        assertTrue(events().isEmpty())
    }

    // Identity, double tap and operations in flight.

    @Test fun doubleTapRecordsOneConfirmationAndOneRevocationEachWithOneIdentity() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            scenario.onActivity {
                val button = view(it, "profile:review:confirm")
                button.performClick()
                button.performClick()
                // In flight: the stale reference is ignored and the new screen blocks every action.
                assertEquals(s(R.string.profile_review_recording), text(it, "profile:review:recording"))
                assertFalse(view(it, "profile:review:confirm").isEnabled)
                assertFalse(view(it, "profile:review:back").isEnabled)
            }
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 1))
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:confirm")
            scenario.onActivity {
                val button = view(it, "profile:revoke:confirm")
                button.performClick()
                button.performClick()
                assertEquals(s(R.string.profile_revoke_recording), text(it, "profile:revoke:recording"))
            }
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_revoked, 1))
            gateIs(scenario, s(R.string.profile_gate_revoked, 1))
        }
        val stored = events()
        assertEquals(listOf(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.REVOKE), stored.map { it.kind })
        assertEquals(issued.toList(), stored.map { it.operationId })
        assertEquals(2, screenRepository().requests.size)
    }

    @Test fun operationInFlightBlocksOtherActionsAndSurvivesRecreation() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            val hold = CountDownLatch(1)
            screenRepository().holdBeforeWrite = hold
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:review:recording")
            scenario.recreate()
            awaitView(scenario, "profile:review:recording", s(R.string.profile_review_recording))
            scenario.onActivity {
                // The previous state is shown, never the expected result.
                assertEquals(s(R.string.profile_gate_unconfirmed, 1), text(it, "profile:review:gate"))
                assertFalse(view(it, "profile:review:confirm").isEnabled)
                assertFalse(view(it, "profile:review:back").isEnabled)
                assertNull(viewOrNull(it, "profile:confirmation:notice"))
            }
            hold.countDown()
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 1))
            gateIs(scenario, s(R.string.profile_gate_confirmed, 1))
        }
        assertEquals(1, events().size)
        assertEquals(1, issued.size)
    }

    // Recreation from saved state (process death): ADR 0014, section 9.3.

    private fun model(repository: ControlledProfileRepository, state: Bundle?): ClinicalProfileModel {
        lateinit var model: ClinicalProfileModel
        instrumentation.runOnMainSync {
            model = ClinicalProfileModel(ClinicalProfiles(repository, ProfileClock { 1_790_000_000_000 }, "test/ui", zones),
                repository, state, ids)
            model.ensureLoaded()
        }
        return model
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

    /** Opens the review on [model] and starts confirming; returns the saved state taken while the write is in flight. */
    private fun confirmInFlight(model: ClinicalProfileModel): Bundle {
        awaitModel(model) { it.evaluated != null }
        instrumentation.runOnMainSync { model.openReview() }
        awaitModel(model) { (it.decision as? Decision.Review)?.target != null && it.evaluated != null }
        instrumentation.runOnMainSync { model.confirm() }
        awaitModel(model) { it.recording != null }
        lateinit var state: Bundle
        instrumentation.runOnMainSync { state = model.snapshot() }
        return state
    }

    private fun rendered(model: ClinicalProfileModel): LinearLayout {
        lateinit var panel: LinearLayout
        instrumentation.runOnMainSync {
            panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            ClinicalProfileScreen(context, panel, model, null).render()
        }
        return panel
    }

    private fun shown(panel: View, tag: String): String = (panel.findViewWithTag<TextView>(tag)!!).text.toString()

    @Test fun commitFinishedBeforeRecreationIsShownAsRecordedNeverAsConflictOrReconfirmation() {
        save(0, content())
        val first = controlled()
        val hold = CountDownLatch(1)
        first.holdAfterCommit = hold
        val a = model(first, null)
        val state = confirmInFlight(a)
        assertTrue(first.committed.await(8, TimeUnit.SECONDS))
        val confirmation = events().single()
        // B: same operation stored, nothing else changed.
        val b = model(controlled(), Bundle(state))
        awaitModel(b) { it.notice != null }
        instrumentation.runOnMainSync {
            assertEquals(ConfirmationNotice.Recorded(ConfirmationEventKind.CONFIRM, 1, confirmation.seq, true), b.notice)
            assertNull(b.pending)
            assertNull(b.decision)
        }
        rendered(b).let {
            assertEquals(s(R.string.profile_notice_confirm_replayed, 1), shown(it, "profile:confirmation:notice"))
            assertEquals(s(R.string.profile_gate_confirmed, 1), shown(it, "profile:gate"))
        }
        // C: another connection revoked after the commit, so the observed event is behind. Still "registered", with the
        // current state, and never reactivated or retried.
        externalRevoke()
        val c = model(controlled(), Bundle(state))
        awaitModel(c) { it.notice != null }
        rendered(c).let {
            assertEquals(s(R.string.profile_notice_confirm_replayed_revoked), shown(it, "profile:confirmation:notice"))
            assertEquals(s(R.string.profile_gate_revoked, 1), shown(it, "profile:gate"))
            assertNull(it.findViewWithTag<View>("profile:confirmation:code"))
        }
        hold.countDown()
        awaitModel(a) { it.recording == null }
        instrumentation.runOnMainSync {
            assertEquals(ConfirmationNotice.Recorded(ConfirmationEventKind.CONFIRM, 1, confirmation.seq, false), a.notice)
            listOf(a, b, c).forEach { it.dispose() }
        }
        assertEquals(listOf(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.REVOKE), events().map { it.kind })
        assertEquals(1, issued.size)
    }

    @Test fun unprovenReadKeepsTheOperationAndTheRetryReusesItsIdentity() {
        save(0, content())
        val first = controlled()
        val hold = CountDownLatch(1)
        first.holdBeforeWrite = hold
        val a = model(first, null)
        val state = confirmInFlight(a)
        val failing = controlled().apply { readFailure = ProfileFailure.READ_FAILED }
        val b = model(failing, Bundle(state))
        awaitModel(b) { it.undetermined != null }
        instrumentation.runOnMainSync {
            assertEquals(ProfileFailure.READ_FAILED, b.undetermined)
            assertNotNull(b.pending)
            assertTrue(b.decision is Decision.Review)
            assertNull(b.notice)
            // Still kept for a later recreation.
            assertEquals(state.getString("pendingId"), b.snapshot().getString("pendingId"))
        }
        rendered(b).let {
            assertEquals(s(R.string.profile_review_undetermined, ProfileFailure.READ_FAILED.code), shown(it, "profile:undetermined"))
            assertNull(it.findViewWithTag<View>("profile:review:confirm"))
            assertTrue(it.findViewWithTag<View>("profile:review:retry").isEnabled)
            assertFalse(texts(it).any { t -> t.contains("datos sin confirmar") || t.contains("Datos confirmados") })
        }
        // A proven read finds nothing stored and nothing changed: the review stays open with the same identity.
        failing.readFailure = null
        instrumentation.runOnMainSync { b.retry() }
        awaitModel(b) { it.evaluated != null }
        instrumentation.runOnMainSync {
            assertNull(b.undetermined)
            assertNull(b.notice)
        }
        assertTrue(rendered(b).findViewWithTag<View>("profile:review:confirm").isEnabled)
        instrumentation.runOnMainSync { b.confirm() }
        awaitModel(b) { it.notice != null }
        val stored = events().single()
        assertEquals(issued.single(), stored.operationId)
        // The first write finally runs and finds its own operation: a replay, nothing inserted.
        hold.countDown()
        awaitModel(a) { it.recording == null }
        instrumentation.runOnMainSync {
            assertEquals(ConfirmationNotice.Recorded(ConfirmationEventKind.CONFIRM, 1, stored.seq, true), a.notice)
            a.dispose(); b.dispose()
        }
        assertEquals(1, events().size)
    }

    @Test fun onlyAnOperationThatIsNotStoredIsClosedAsStaleOrChanged() {
        save(0, content())
        val first = controlled()
        first.holdBeforeWrite = CountDownLatch(1)
        first.writeFailure = ProfileFailure.SAVE_FAILED
        val a = model(first, null)
        val state = confirmInFlight(a)
        // Another connection confirmed and revoked: same version, other events.
        externalConfirm()
        externalRevoke()
        val b = model(controlled(), Bundle(state))
        awaitModel(b) { it.notice != null }
        rendered(b).let {
            assertEquals(s(R.string.profile_notice_state_changed), shown(it, "profile:confirmation:notice"))
            assertEquals(s(R.string.profile_notice_code, ProfileFailure.CONFIRMATION_STATE_CHANGED.code),
                shown(it, "profile:confirmation:code"))
            assertEquals(s(R.string.profile_gate_revoked, 1), shown(it, "profile:gate"))
        }
        // A newer version saved meanwhile: stale.
        save(1, content(ratioValue = entered("11")))
        val c = model(controlled(), Bundle(state))
        awaitModel(c) { it.notice != null }
        rendered(c).let {
            assertEquals(s(R.string.profile_notice_stale, 2), shown(it, "profile:confirmation:notice"))
            // The confirmation of version 1 was revoked, so nothing is superseded.
            assertEquals(s(R.string.profile_gate_unconfirmed, 2), shown(it, "profile:gate"))
        }
        instrumentation.runOnMainSync {
            assertNull(c.pending); assertNull(c.decision)
            a.dispose(); b.dispose(); c.dispose()
        }
        assertEquals(2, events().size)
    }

    /** No success or failure outcome is on screen: only the state of the read and, if any, the undetermined notice. */
    private fun assertNoOutcome(panel: View) {
        assertNull(panel.findViewWithTag<View>("profile:confirmation:notice"))
        assertNull(panel.findViewWithTag<View>("profile:confirmation:code"))
        val outcomes = listOf(R.string.profile_notice_confirmed, R.string.profile_notice_revoked,
            R.string.profile_notice_confirm_replayed, R.string.profile_notice_revoke_replayed).map { s(it, 1) } +
            listOf(s(R.string.profile_notice_write_failed, ProfileFailure.SAVE_FAILED.code),
                s(R.string.profile_notice_revoke_failed, ProfileFailure.SAVE_FAILED.code),
                s(R.string.profile_notice_confirm_replayed_revoked))
        val all = texts(panel)
        outcomes.forEach { outcome -> assertFalse(outcome, all.contains(outcome)) }
        assertFalse(all.any { it.contains("No se pudo registrar") })
    }

    /** While the read is pending or unproven no state of the version is shown either: nothing confirmed or unconfirmed. */
    private fun assertNoState(panel: View) {
        assertNull(panel.findViewWithTag<View>("profile:gate"))
        assertNull(panel.findViewWithTag<View>("profile:review:gate"))
        assertFalse(texts(panel).any { it.contains("Datos confirmados") || it.contains("datos sin confirmar") ||
            it.contains("Confirmación retirada") })
    }

    /**
     * Regression: write failure, saved state, recreation, read failure and recovery. The failure notice saved with the
     * state must not stay on screen while the read is pending or unproven, and the retry keeps the operation identity.
     */
    private fun failedWriteThenUnprovenReadThenRecovery(open: (ClinicalProfileModel) -> Unit,
                                                       act: (ClinicalProfileModel) -> Unit,
                                                       failure: ConfirmationNotice.Failed,
                                                       undeterminedText: Int,
                                                       pendingTag: String): ClinicalProfileModel {
        val first = controlled().apply { writeFailure = ProfileFailure.SAVE_FAILED }
        val a = model(first, null)
        awaitModel(a) { it.evaluated != null }
        instrumentation.runOnMainSync { open(a) }
        // A review is bound to its fresh read before acting; a revocation question is bound at once.
        awaitModel(a) { it.evaluated != null && (it.decision as? Decision.Review)?.target != null || it.decision is Decision.Revoke }
        instrumentation.runOnMainSync { act(a) }
        awaitModel(a) { it.recording == null && it.notice != null }
        lateinit var saved: Bundle
        lateinit var request: ConfirmationRequest
        instrumentation.runOnMainSync {
            assertEquals(failure, a.notice)
            request = a.pending!!.request
            saved = a.snapshot()
            a.dispose()
        }
        // The saved state carries the failed attempt's notice and the complete request.
        assertEquals(request.operationId.value, saved.getString("pendingId"))
        assertNotNull(saved.getString("noticeKind"))
        val eventsBefore = events()

        // Recreation: the read is first pending, then fails.
        val hold = CountDownLatch(1)
        val second = controlled().apply { readFailure = ProfileFailure.READ_FAILED; holdBeforeRead = hold }
        val b = model(second, Bundle(saved))
        assertTrue(second.readStarted.await(8, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertNull(b.gate)
            assertNull(b.notice)
            assertEquals(request, b.pending!!.request)
        }
        rendered(b).let {
            assertNoOutcome(it)
            assertNoState(it)
            assertNull(it.findViewWithTag<View>("profile:undetermined"))
        }
        hold.countDown()
        awaitModel(b) { it.undetermined != null }
        instrumentation.runOnMainSync {
            assertEquals(ProfileFailure.READ_FAILED, b.undetermined)
            assertNull(b.notice)
            assertEquals(request, b.pending!!.request)
            // Saved again while unproven: same request and identity, no stale outcome.
            val again = b.snapshot()
            assertEquals(request.operationId.value, again.getString("pendingId"))
            assertNull(again.getString("noticeKind"))
        }
        rendered(b).let {
            assertNoOutcome(it)
            assertNoState(it)
            assertEquals(s(undeterminedText, ProfileFailure.READ_FAILED.code), shown(it, "profile:undetermined"))
            assertNull(it.findViewWithTag<View>(pendingTag))
        }
        assertEquals(eventsBefore, events())

        // Recovery: nothing was stored and nothing changed, so the decision reopens with the same identity.
        second.holdBeforeRead = null
        second.readFailure = null
        instrumentation.runOnMainSync { b.retry() }
        awaitModel(b) { it.evaluated != null }
        instrumentation.runOnMainSync {
            assertNull(b.undetermined)
            assertNull(b.notice)
            assertEquals(request, b.pending!!.request)
        }
        rendered(b).let {
            assertNoOutcome(it)
            assertTrue(it.findViewWithTag<View>(pendingTag).isEnabled)
        }
        instrumentation.runOnMainSync { act(b) }
        awaitModel(b) { it.recording == null && it.notice != null }
        assertEquals(request, second.requests.single())
        assertEquals(request.operationId, events().last().operationId)
        assertEquals(eventsBefore.size + 1, events().size)
        return b
    }

    @Test fun confirmationAfterFailedWriteAndUnprovenReadKeepsItsIdentityWithoutStaleOutcomes() {
        save(0, content())
        val b = failedWriteThenUnprovenReadThenRecovery(
            open = { it.openReview() },
            act = { it.confirm() },
            failure = ConfirmationNotice.Failed(ConfirmationEventKind.CONFIRM, 1, ProfileFailure.SAVE_FAILED),
            undeterminedText = R.string.profile_review_undetermined,
            pendingTag = "profile:review:confirm")
        instrumentation.runOnMainSync {
            assertEquals(ConfirmationNotice.Recorded(ConfirmationEventKind.CONFIRM, 1, 1, false), b.notice)
            b.dispose()
        }
        assertEquals(1, issued.size)
        assertEquals(issued.single(), events().single().operationId)
    }

    @Test fun revocationAfterFailedWriteAndUnprovenReadKeepsItsIdentityWithoutStaleOutcomes() {
        save(0, content())
        externalConfirm()
        val b = failedWriteThenUnprovenReadThenRecovery(
            open = { it.proposeRevoke() },
            act = { it.revoke() },
            failure = ConfirmationNotice.Failed(ConfirmationEventKind.REVOKE, 1, ProfileFailure.SAVE_FAILED),
            undeterminedText = R.string.profile_revoke_undetermined,
            pendingTag = "profile:revoke:confirm")
        instrumentation.runOnMainSync {
            assertEquals(ConfirmationNotice.Recorded(ConfirmationEventKind.REVOKE, 1, 2, false), b.notice)
            b.dispose()
        }
        assertEquals(1, issued.size)
        assertEquals(listOf(ConfirmationEventKind.CONFIRM, ConfirmationEventKind.REVOKE), events().map { it.kind })
        assertEquals(issued.single(), events().last().operationId)
    }

    // Revocation, reconfirmation and history.

    @Test fun revokeAndReconfirmAreSeparateActionsAndHistoryShowsEveryFact() {
        save(0, content(sensitivity = entered("0")))
        launch().use { scenario ->
            openProfile(scenario)
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 1))
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:question")
            // Cancelling records nothing.
            click(scenario, "profile:revoke:cancel")
            gateIs(scenario, s(R.string.profile_gate_confirmed, 1))
            assertEquals(1, events().size)
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:confirm")
            click(scenario, "profile:revoke:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_revoked, 1))
            // Reconfirming needs a new review and a new operation.
            scenario.onActivity { assertNull(viewOrNull(it, "profile:review:confirm")) }
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 1))
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:value:carb_ratio@0-1440")
            scenario.onActivity { type(it, "profile:value:carb_ratio@0-1440", "12") }
            click(scenario, "profile:save")
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 2))
            gateIs(scenario, s(R.string.profile_gate_superseded, 2, 1))
            click(scenario, "profile:history")
            awaitView(scenario, "profile:history:1:event:1")
            scenario.onActivity {
                assertTrue(text(it, "profile:history:1:event:1").startsWith("Confirmada el "))
                assertTrue(text(it, "profile:history:1:event:2").startsWith("Confirmación retirada el "))
                assertTrue(text(it, "profile:history:1:event:3").startsWith("Confirmada el "))
                assertEquals(s(R.string.profile_history_superseded, 2), text(it, "profile:history:1:event:3:superseded"))
                assertNull(viewOrNull(it, "profile:history:2:event:1"))
                assertNeverApproved(it)
            }
            // Restoring a confirmed version says the new version will not inherit its confirmation.
            click(scenario, "profile:history:1:restore")
            awaitView(scenario, "profile:restore:question")
            scenario.onActivity {
                assertTrue(text(it, "profile:restore:question").contains(s(R.string.profile_restore_no_inherit, 1)))
            }
        }
        val stored = events()
        assertEquals(3, stored.size)
        assertEquals(3, stored.map { it.operationId }.toSet().size)
        assertEquals(issued.toList(), stored.map { it.operationId })
    }

    // Conflicts and failures.

    @Test fun conflictsWhileReviewingAreVisibleAndRecordNothing() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            save(1, content(ratioValue = entered("11")))
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_stale, 2))
            gateIs(scenario, s(R.string.profile_gate_unconfirmed, 2))
            scenario.onActivity {
                assertEquals(s(R.string.profile_notice_code, ProfileFailure.CONFIRMATION_STALE_VERSION.code),
                    text(it, "profile:confirmation:code"))
            }
            assertTrue(events().isEmpty())
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            externalConfirm()
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_state_changed))
            gateIs(scenario, s(R.string.profile_gate_confirmed, 2))
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:confirm")
            externalRevoke()
            click(scenario, "profile:revoke:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_revoke_state_changed))
            gateIs(scenario, s(R.string.profile_gate_revoked, 2))
        }
        assertEquals(2, events().size)
    }

    @Test fun readFailuresAreNeverShownAsUnconfirmed() {
        save(0, content())
        for (reason in listOf(ProfileFailure.READ_FAILED, ProfileFailure.INVALID_RECORD, ProfileFailure.UNSUPPORTED_SCHEMA,
            ProfileFailure.CORRUPT_STORAGE)) {
            MainActivity.profileRepositoryFactory = { controlled().apply { readFailure = reason } }
            launch().use { scenario ->
                openProfile(scenario)
                awaitView(scenario, "profile:status", s(R.string.profile_read_failed, reason.code))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "profile:review:open"))
                    assertNull(viewOrNull(it, "profile:revoke:open"))
                    assertNull(viewOrNull(it, "profile:gate"))
                    // The failure says so explicitly; no state of the version is shown.
                    assertFalse(texts(it.findViewById(R.id.screen_content)).any { t -> t.contains("datos sin confirmar") })
                    assertNeverApproved(it)
                }
            }
        }
    }

    @Test fun failedWritesConfirmNothingAndTheSameIntentionSucceedsAfterwards() {
        save(0, content())
        launch().use { scenario ->
            openProfile(scenario)
            click(scenario, "profile:review:open")
            awaitView(scenario, "profile:review:confirm")
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("CREATE TRIGGER synthetic_failure AFTER INSERT ON profile_confirmation_events " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            }
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice",
                s(R.string.profile_notice_write_failed, ProfileFailure.SAVE_FAILED.code))
            scenario.onActivity {
                // Nothing is claimed and the review stays open on the same version.
                assertEquals(s(R.string.profile_gate_unconfirmed, 1), text(it, "profile:review:gate"))
                assertTrue(view(it, "profile:review:confirm").isEnabled)
            }
            // The synthetic trigger is not part of the frozen schema, so count the rows directly.
            assertEquals(0L, rawEventCount())
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER synthetic_failure") }
            click(scenario, "profile:review:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_confirmed, 1))
            // Revocation write failure through the controlled port.
            screenRepository().writeFailure = ProfileFailure.SAVE_FAILED
            click(scenario, "profile:revoke:open")
            awaitView(scenario, "profile:revoke:confirm")
            click(scenario, "profile:revoke:confirm")
            awaitView(scenario, "profile:confirmation:notice",
                s(R.string.profile_notice_revoke_failed, ProfileFailure.SAVE_FAILED.code))
            scenario.onActivity { assertEquals(s(R.string.profile_gate_confirmed, 1), text(it, "profile:gate")) }
            screenRepository().writeFailure = null
            click(scenario, "profile:revoke:confirm")
            awaitView(scenario, "profile:confirmation:notice", s(R.string.profile_notice_revoked, 1))
        }
        val stored = events()
        assertEquals(2, stored.size)
        // One identity per intention, reused by its retry.
        assertEquals(issued.toList(), stored.map { it.operationId })
    }

    // Bolo.

    @Test fun bolusSaysTheProfileIsNotUsedEvenWithConfirmedData() {
        save(0, content())
        externalConfirm()
        launch().use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/bolus").performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val all = texts(activity.findViewById(R.id.screen_content))
                assertEquals("Perfil · no se usa para calcular", activity.getString(R.string.profile_absent))
                assertTrue(all.contains(activity.getString(R.string.profile_absent)))
                assertFalse(all.any { it.contains("confirmad", ignoreCase = true) })
                assertFalse(descendants(activity.findViewById(R.id.screen_content))
                    .any { (it.tag as? String)?.startsWith("profile:") == true })
                assertFalse(view(activity, "blocked:calculate").isEnabled)
                assertFalse(view(activity, "blocked:confirm").isEnabled)
            }
        }
        // Bolo reads the profile only for its blocking details (ADR 0017) and never writes to it.
        val limit = SystemClock.uptimeMillis() + 8_000
        while (repositories.sumOf { it.reads.get() } == 0 && SystemClock.uptimeMillis() < limit) SystemClock.sleep(20)
        assertEquals(1, repositories.sumOf { it.reads.get() })
        assertEquals(0, repositories.sumOf { it.saves.get() + it.requests.size })
    }

    // Layouts.

    private fun assertFits(activity: MainActivity, model: ClinicalProfileModel, expectedTag: String) {
        for ((widthDp, heightDp) in listOf(840 to 900, 900 to 840, 411 to 914)) {
            for (scale in listOf(1f, 1.8f)) for (night in listOf(false, true)) {
                val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                    screenWidthDp = widthDp; screenHeightDp = heightDp; fontScale = scale
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
                        else android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val themed = activity.createConfigurationContext(configuration)
                val shell = android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
                val panel = shell.findViewById<LinearLayout>(R.id.screen_content)
                shell.findViewById<TextView>(R.id.screen_title).setText(R.string.settings)
                ClinicalProfileScreen(themed, panel, model, "Europe/Madrid").render()
                val density = themed.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                shell.layout(0, 0, width, height)
                assertNotNull(expectedTag, panel.findViewWithTag<View>(expectedTag))
                descendants(shell).filterIsInstance<TextView>().filter { it.isVisible }.forEach {
                    assertTrue("No width: ${it.tag}", it.width > 0)
                    assertTrue("Clipped text: ${it.tag}", it.layout.height <=
                        it.height - it.compoundPaddingTop - it.compoundPaddingBottom)
                    for (line in 0 until it.layout.lineCount) assertEquals(0, it.layout.getEllipsisCount(line))
                }
                descendants(panel).filterIsInstance<Button>().filter { it.isVisible }.forEach {
                    assertTrue("Small target: ${it.tag}", it.height >= (48 * density).toInt())
                }
            }
        }
    }

    @Test fun confirmationScreensFitExpandedLayoutsAtLargeFontInBothThemes() {
        fun eight(value: (Int) -> ProfileValue) = (0 until 8).map { seg(it * 180, (it + 1) * 180, value(it)) }
        save(0, ProfileContent(1, mgdl, madrid, listOf(ParameterSchedule(ratio, eight { entered("${10 + it}") }),
            ParameterSchedule(isf, eight { if (it % 2 == 0) entered("0") else entered("40") }),
            ParameterSchedule(target, eight { entered("110") }))))
        launch().use { scenario ->
            fun fits(model: ClinicalProfileModel, tag: String) = scenario.onActivity { assertFits(it, model, tag) }
            val model = model(controlled(), null)
            try {
                awaitModel(model) { it.evaluated != null }
                instrumentation.runOnMainSync { model.openReview() }
                awaitModel(model) { (it.decision as? Decision.Review)?.target != null && it.evaluated != null }
                fits(model, "profile:review:segment:insulin_sensitivity@0-180")
                instrumentation.runOnMainSync { model.confirm() }
                awaitModel(model) { it.notice != null }
                fits(model, "profile:confirmation:notice")
                instrumentation.runOnMainSync { model.proposeRevoke() }
                fits(model, "profile:revoke:question")
                instrumentation.runOnMainSync { model.cancelDecision() }
                retired = setOf("Europe/Madrid")
                instrumentation.runOnMainSync { model.retry() }
                awaitModel(model) { it.evaluated != null }
                fits(model, "profile:gate")
                instrumentation.runOnMainSync { model.proposeRevoke(); model.revoke() }
                awaitModel(model) { it.recording == null && it.evaluated != null && it.evaluated?.activeConfirmation == null }
                fits(model, "profile:gate:gaps:0")
                instrumentation.runOnMainSync { model.openHistory() }
                awaitModel(model) { it.evaluated != null }
                fits(model, "profile:history:1:event:2")
            } finally { instrumentation.runOnMainSync { model.dispose() } }
            // An undetermined pending operation and a write failure.
            retired = emptySet()
            save(1, content())
            val holder = controlled().apply { holdBeforeWrite = CountDownLatch(1); writeFailure = ProfileFailure.SAVE_FAILED }
            val a = model(holder, null)
            val state = confirmInFlight(a)
            val b = model(controlled().apply { readFailure = ProfileFailure.READ_FAILED }, Bundle(state))
            try {
                awaitModel(b) { it.undetermined != null }
                fits(b, "profile:undetermined")
                holder.holdBeforeWrite!!.countDown()
                awaitModel(a) { it.notice != null }
                fits(a, "profile:confirmation:code")
            } finally { instrumentation.runOnMainSync { a.dispose(); b.dispose() } }
        }
    }
}
