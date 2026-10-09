package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.AndroidTimeZoneRules
import org.bolusai.next.profile.ControlledProfileRepository
import org.bolusai.next.profile.SqliteClinicalProfileRepository
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
 * ADR 0016, option A: the technical block of Ajustes → Cálculo shows the contract v2 report of the state the screen
 * already presents. Synthetic, isolated database files only; the app's own clinical-profile.db is never opened,
 * nothing touches the network and calculation stays blocked in every state. Bolo and Diagnóstico are covered by
 * BolusProfileDetailsDeviceTest (ADR 0017).
 */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileUnavailabilityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "synthetic-profile-unavailability-${UUID.randomUUID()}.db"
    private val policy = "input.profile.policy_not_approved [profile.not_approved_for_calculation]"
    private val pending = "input.profile.unknown [profile.read.pending]"
    private val rejected = "profile_unavailability.unreadable_reason_not_supported"

    @Volatile private var retired = emptySet<String>()
    private val zones = TimeZoneRules { it !in retired && AndroidTimeZoneRules.exists(it) }
    private val repositories = CopyOnWriteArrayList<ControlledProfileRepository>()
    /** Applied to every repository the screen opens, so a recreated model keeps the same synthetic behaviour. */
    @Volatile private var configure: (ControlledProfileRepository) -> Unit = {}

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = {
            ControlledProfileRepository(SqliteClinicalProfileRepository(context, name)).also { configure(it); repositories.add(it) }
        }
        MainActivity.profileClock = ProfileClock { 1_790_000_000_000 }
        MainActivity.profileTimeZoneRules = zones
        MainActivity.profileOperationIds = OperationIds { OperationId(UUID.randomUUID().toString()) }
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
        activity.findViewById<View>(R.id.screen_content).findViewWithTag(tag)

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

    private fun openProfile(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/menu").performClick() }
        click(scenario, "link:/settings")
        click(scenario, "settings:CALCULATION")
        await(scenario, "profile:safety") { viewOrNull(it, "profile:safety") != null }
    }

    private fun block(activity: MainActivity): TextView = viewOrNull(activity, "profile:unavailability") as TextView
    private fun lines(activity: MainActivity) = block(activity).text.toString().split('\n')

    /** Waits until the block shows exactly [expected]; any line of an earlier state makes the comparison fail. */
    private fun shows(scenario: ActivityScenario<MainActivity>, vararg expected: String) {
        await(scenario, expected.joinToString(" | ")) { activity ->
            (viewOrNull(activity, "profile:unavailability") as? TextView)?.text?.toString()?.split('\n') == expected.toList()
        }
        scenario.onActivity { activity ->
            assertEquals(expected.toList(), lines(activity))
            assertEquals(ProfileVersion.NOT_APPROVED_CODE,
                (viewOrNull(activity, "profile:block") as TextView).text.toString())
        }
    }

    private fun expand(scenario: ActivityScenario<MainActivity>) {
        click(scenario, "profile:unavailability:toggle")
        scenario.onActivity { assertTrue(block(it).isVisible) }
    }

    @Test fun blockStartsFoldedAndShowsExactCodesForEveryProvokedState() {
        // E5.
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, "input.profile.missing [profile.history.missing]", policy)
            scenario.onActivity {
                assertFalse("Folded by default (A3)", block(it).isVisible)
                assertTrue(block(it).isTextSelectable)
            }
            expand(scenario)
            click(scenario, "profile:unavailability:toggle")
            scenario.onActivity { assertFalse(block(it).isVisible) }
        }
        // E6: values missing, unconfirmed.
        save(0, content(sensitivity = ProfileValue.NotConfigured))
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, "input.profile.incomplete [profile.gate.incomplete]", policy,
                "input.profile.unconfirmed [profile.confirmation.missing]")
        }
        // E7, E9, E10, E8 and E11 after each external fact.
        save(1, content())
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.missing]")
        }
        confirm()
        launch().use { scenario -> openProfile(scenario); shows(scenario, policy) }
        revoke()
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.revoked]")
        }
        confirm()
        retired = setOf("Europe/Madrid")
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, "input.profile.invalid [profile.gate.time_zone_unrecognized]", policy)
        }
        retired = emptySet()
        save(2, content(sensitivity = entered("45")))
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, policy,
                "input.profile.unconfirmed [profile.confirmation.missing, profile.confirmation.superseded]")
        }
    }

    @Test fun readFailuresShowTheirOwnCauseAndAKnownRejectionShowsItsIdentifier() {
        save(0, content())
        listOf(
            ProfileFailure.READ_FAILED to "input.profile.persistence_failed [profile.storage.read_failed]",
            ProfileFailure.CORRUPT_STORAGE to "input.profile.persistence_failed [profile.storage.corrupt]",
            ProfileFailure.INVALID_RECORD to "input.profile.invalid [profile.storage.invalid_record]",
            ProfileFailure.UNSUPPORTED_SCHEMA to "input.profile.parse_failed [profile.storage.unsupported_schema]",
        ).forEach { (reason, cause) ->
            configure = { it.readFailure = reason }
            launch().use { scenario ->
                openProfile(scenario)
                // The report is ordered by code, so read failures may come before or after the policy line.
                shows(scenario, *listOf(cause, policy).sortedBy { it.substringBefore(' ') }.toTypedArray())
            }
        }
        // A failure that is not a read failure reaches the screen only through a defective producer: the translator
        // refuses it, the block shows the stable identifier and the screen keeps blocking (ADR 0016, A4).
        configure = { it.readFailure = ProfileFailure.SAVE_FAILED }
        launch().use { scenario ->
            openProfile(scenario)
            shows(scenario, rejected)
            scenario.onActivity { activity ->
                assertNull(viewOrNull(activity, "profile:review:open"))
                assertNull(viewOrNull(activity, "profile:revoke:open"))
                assertNull(viewOrNull(activity, "profile:gate"))
            }
        }
    }

    @Test fun pendingReadIsReplacedByTheResultWithoutKeepingItsCodes() {
        save(0, content())
        val hold = CountDownLatch(1)
        configure = { it.holdBeforeRead = hold }
        launch().use { scenario ->
            openProfile(scenario)
            assertTrue(repositories.last().readStarted.await(8, TimeUnit.SECONDS))
            shows(scenario, policy, pending)
            expand(scenario)
            hold.countDown()
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.missing]")
            scenario.onActivity { activity ->
                assertFalse(lines(activity).contains(pending))
                // The block stays open while the same screen re-renders with the new state.
                assertTrue(block(activity).isVisible)
            }
        }
    }

    @Test fun retryAfterAFailedReadNeverKeepsCodesOfTheEarlierState() {
        save(0, content())
        configure = { it.readFailure = ProfileFailure.READ_FAILED }
        launch().use { scenario ->
            openProfile(scenario)
            val failed = "input.profile.persistence_failed [profile.storage.read_failed]"
            shows(scenario, failed, policy)
            expand(scenario)
            val screenRepository = repositories.last()
            val hold = CountDownLatch(1)
            screenRepository.readFailure = null
            screenRepository.readStarted = CountDownLatch(1)
            screenRepository.holdBeforeRead = hold
            click(scenario, "profile:retry")
            assertTrue(screenRepository.readStarted.await(8, TimeUnit.SECONDS))
            shows(scenario, policy, pending)
            scenario.onActivity { assertFalse(lines(it).contains(failed)) }
            hold.countDown()
            shows(scenario, policy, "input.profile.unconfirmed [profile.confirmation.missing]")
            scenario.onActivity { activity ->
                assertFalse(lines(activity).contains(failed))
                assertFalse(lines(activity).contains(pending))
            }
            // Recreation keeps the model state but the block starts folded again (A3) with the same codes.
            scenario.recreate()
            await(scenario, "profile:unavailability after recreation") { viewOrNull(it, "profile:unavailability") != null }
            scenario.onActivity { activity ->
                assertFalse("Folded after recreation (A3)", block(activity).isVisible)
                assertEquals(listOf(policy, "input.profile.unconfirmed [profile.confirmation.missing]"), lines(activity))
            }
        }
    }
}
