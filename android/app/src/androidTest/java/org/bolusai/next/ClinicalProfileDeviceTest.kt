package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.profile.AndroidTimeZoneRules
import org.bolusai.next.profile.SqliteClinicalProfileRepository
import org.bolusai.next.ui.ClinicalProfileModel
import org.bolusai.next.ui.ClinicalProfileScreen
import org.bolusai.profile.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Clinical profile capture UI (ADR 0012). Synthetic isolated databases; the app's own files are never opened. */
@RunWith(AndroidJUnit4::class)
class ClinicalProfileDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "synthetic-profile-ui-${UUID.randomUUID()}.db"
    private val mgdl: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MG_DL)
    private val mmol: Setting<GlucoseUnit> = Setting.Declared(GlucoseUnit.MMOL_L)
    private val madrid: Setting<ProfileTimeZone> = Setting.Declared(ProfileTimeZone("Europe/Madrid"))

    @Before fun isolate() {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        MainActivity.profileRepositoryFactory = { SqliteClinicalProfileRepository(it, name) }
        MainActivity.profileClock = ProfileClock { 1_790_000_000_000 }
    }

    @After fun clear() {
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
        MainActivity.profileClock = null
        context.deleteDatabase(name)
    }

    private fun entered(text: String) = ProfileValue.Entered(CanonicalDecimal(text))
    private fun content(unit: Setting<GlucoseUnit> = mgdl, ratio: ProfileValue = entered("10"),
                        sensitivity: ProfileValue = entered("40"), target: ProfileValue = entered("110")) =
        ProfileContent(1, unit, madrid, listOf(
            ParameterSchedule.allDay(ProfileParameter.CARB_RATIO, ratio),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, sensitivity),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, target)))

    private fun seed(vararg writes: ProfileWrite): List<ProfileVersion> = SqliteClinicalProfileRepository(context, name).use { repo ->
        writes.map { (repo.save(it, 1_780_000_000_000, "test/seed") as ProfileSave.Saved).version }
    }

    private fun stored(): List<ProfileVersion> = SqliteClinicalProfileRepository(context, name).use {
        (it.readVersions() as ProfileRead.Loaded).versions.sortedBy { v -> v.version }
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
    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) = scenario.onActivity { view(it, tag).performClick() }

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

    private fun openProfile(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/menu").performClick() }
        click(scenario, "link:/settings")
        click(scenario, "settings:CALCULATION")
        awaitView(scenario, "profile:safety")
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun allDay(value: String) = s(R.string.profile_all_day, value)
    private fun valueLabel(parameter: Int, value: String) = s(R.string.profile_param_value, s(parameter), allDay(value))

    @Test fun createFirstVersionKeepsZeroDistinctFromAbsentAndSurvivesRecreation() {
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:status", s(R.string.profile_missing))
            scenario.onActivity { assertEquals(ProfileVersion.NOT_APPROVED_CODE, text(it, "profile:block")) }
            click(scenario, "profile:new")
            awaitView(scenario, "profile:save")
            scenario.onActivity {
                // Nothing is configured and nothing can be saved yet.
                assertEquals("", text(it, "profile:value:carb_ratio"))
                assertFalse(view(it, "profile:save").isEnabled)
                assertTrue((view(it, "profile:unit:none") as RadioButton).isChecked)
                assertFalse(view(it, "profile:value:glucose_target").isEnabled)
                type(it, "profile:value:carb_ratio", "0")
                assertEquals(s(R.string.profile_preview_value, "0 g/U"), text(it, "profile:preview:carb_ratio"))
                view(it, "profile:unit:mg_dl").performClick()
            }
            awaitView(scenario, "profile:value:glucose_target")
            scenario.onActivity {
                assertTrue(view(it, "profile:value:glucose_target").isEnabled)
                assertEquals("0", text(it, "profile:value:carb_ratio"))
                type(it, "profile:value:glucose_target", "1.000")
                assertEquals(s(R.string.profile_preview_error, ProfileFailure.AMBIGUOUS_DECIMAL.code),
                    text(it, "profile:preview:glucose_target"))
                assertFalse(view(it, "profile:save").isEnabled)
                type(it, "profile:value:glucose_target", "110,50")
                assertEquals(s(R.string.profile_preview_value, "110.5 mg/dL"), text(it, "profile:preview:glucose_target"))
                assertEquals(s(R.string.profile_preview_missing), text(it, "profile:preview:insulin_sensitivity"))
                type(it, "profile:time_zone", "Europe/Madrid")
                assertTrue(view(it, "profile:save").isEnabled)
            }
            // Unsaved editor survives recreation with the same text and previews.
            scenario.recreate()
            awaitView(scenario, "profile:value:glucose_target")
            scenario.onActivity {
                assertEquals("110,50", text(it, "profile:value:glucose_target"))
                assertEquals("0", text(it, "profile:value:carb_ratio"))
                assertTrue((view(it, "profile:unit:mg_dl") as RadioButton).isChecked)
            }
            assertEquals(emptyList<ProfileVersion>(), stored())
            click(scenario, "profile:save")
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 1))
            awaitView(scenario, "profile:current:carb_ratio", valueLabel(R.string.profile_param_carb_ratio, "0 g/U"))
            scenario.onActivity {
                assertEquals(valueLabel(R.string.profile_param_insulin_sensitivity, s(R.string.profile_not_configured)),
                    text(it, "profile:current:insulin_sensitivity"))
                assertEquals(s(R.string.profile_origin_manual), text(it, "profile:current:origin"))
            }
            scenario.recreate()
            awaitView(scenario, "profile:current:glucose_target", valueLabel(R.string.profile_param_glucose_target, "110.5 mg/dL"))
        }
        val v1 = stored().single()
        assertEquals(ProfileValue.Entered(CanonicalDecimal("0")), v1.content.schedule(ProfileParameter.CARB_RATIO).segments.single().value)
        assertEquals(ProfileValue.NotConfigured, v1.content.schedule(ProfileParameter.INSULIN_SENSITIVITY).segments.single().value)
        assertEquals(1_790_000_000_000, v1.createdAtEpochMs)
        assertTrue(v1.writer.startsWith("android/"))
    }

    @Test fun unitChangeIsSavedAloneAndOlderValuesKeepTheirOriginalUnit() {
        val (v1) = seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null))
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:edit")
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:unit:mmol_l")
            click(scenario, "profile:unit:mmol_l")
            awaitView(scenario, "profile:unit_lock", s(R.string.profile_unit_lock))
            scenario.onActivity {
                // Values typed under mg/dL are gone and cannot be typed again in this version.
                assertEquals("", text(it, "profile:value:glucose_target"))
                assertEquals("", text(it, "profile:value:insulin_sensitivity"))
                assertFalse(view(it, "profile:value:glucose_target").isEnabled)
                assertEquals("10", text(it, "profile:value:carb_ratio"))
            }
            // Switching back does not restore the numbers.
            click(scenario, "profile:unit:mg_dl")
            awaitView(scenario, "profile:value:glucose_target")
            scenario.onActivity {
                assertNull(viewOrNull(it, "profile:unit_lock"))
                assertEquals("", text(it, "profile:value:glucose_target"))
                view(it, "profile:unit:mmol_l").performClick()
            }
            awaitView(scenario, "profile:unit_lock")
            scenario.recreate()
            awaitView(scenario, "profile:unit_lock")
            scenario.onActivity { assertTrue(view(it, "profile:save").isEnabled) }
            click(scenario, "profile:save")
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 2))
            awaitView(scenario, "profile:current:unit", s(R.string.profile_unit_value, "mmol/L"))
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:value:glucose_target")
            scenario.onActivity {
                assertNull(viewOrNull(it, "profile:unit_lock"))
                assertTrue(view(it, "profile:value:glucose_target").isEnabled)
                type(it, "profile:value:glucose_target", "6,1")
                assertEquals(s(R.string.profile_preview_value, "6.1 mmol/L"), text(it, "profile:preview:glucose_target"))
            }
            click(scenario, "profile:save")
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 3))
            click(scenario, "profile:history")
            awaitView(scenario, "profile:history:1:glucose_target",
                valueLabel(R.string.profile_param_glucose_target, "110 mg/dL"))
            scenario.onActivity {
                assertEquals(s(R.string.profile_unit_value, "mg/dL"), text(it, "profile:history:1:unit"))
                assertEquals(valueLabel(R.string.profile_param_glucose_target, s(R.string.profile_not_configured)),
                    text(it, "profile:history:2:glucose_target"))
                assertEquals(valueLabel(R.string.profile_param_glucose_target, "6.1 mmol/L"), text(it, "profile:history:3:glucose_target"))
            }
        }
        val versions = stored()
        assertEquals(v1, versions[0])
        assertEquals(listOf(mgdl, mmol, mmol), versions.map { it.content.glucoseUnit })
        assertFalse(versions[1].content.hasGlucoseDependentValues)
    }

    @Test fun concurrentWriteIsAVisibleConflictAndNothingIsOverwritten() {
        seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null))
        lateinit var external: ProfileVersion
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:edit")
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:value:carb_ratio")
            scenario.onActivity { type(it, "profile:value:carb_ratio", "12") }
            external = seed(ProfileWrite(1, content(ratio = entered("15")), ProfileOrigin.MANUAL, null)).single()
            click(scenario, "profile:save")
            awaitView(scenario, "profile:editor:status", s(R.string.profile_status_conflict, ProfileFailure.CONFLICT.code))
            scenario.onActivity { assertEquals("12", text(it, "profile:value:carb_ratio")) }
        }
        assertEquals(listOf(1L, 2L), stored().map { it.version })
        assertEquals(external, stored().last())
    }

    @Test fun restoreNeedsConfirmationKeepsOriginalUnitAndSavesExactCopy() {
        val (v1, _) = seed(ProfileWrite(0, content(ratio = entered("0")), ProfileOrigin.MANUAL, null),
            ProfileWrite(1, content(unit = mmol, sensitivity = ProfileValue.NotConfigured, target = ProfileValue.NotConfigured),
                ProfileOrigin.MANUAL, null))
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:history")
            click(scenario, "profile:history")
            awaitView(scenario, "profile:history:1:restore")
            scenario.onActivity { assertNull(viewOrNull(it, "profile:history:2:restore")) }
            click(scenario, "profile:history:1:restore")
            val question = s(R.string.profile_restore_question, 1, 3) + "\n" +
                s(R.string.profile_restore_unit_warning, 1, "mg/dL", "mmol/L")
            awaitView(scenario, "profile:restore:question", question)
            scenario.recreate()
            awaitView(scenario, "profile:restore:question", question)
            click(scenario, "profile:restore:cancel")
            awaitView(scenario, "profile:history:1:restore")
            assertEquals(2, stored().size)
            click(scenario, "profile:history:1:restore")
            awaitView(scenario, "profile:restore:confirm", s(R.string.profile_restore_confirm, 1))
            click(scenario, "profile:restore:confirm")
            awaitView(scenario, "profile:editor:origin", s(R.string.profile_editor_origin_restored, 1))
            scenario.onActivity {
                assertTrue((view(it, "profile:unit:mg_dl") as RadioButton).isChecked)
                assertEquals("110", text(it, "profile:value:glucose_target"))
                assertEquals("0", text(it, "profile:value:carb_ratio"))
                assertNull(viewOrNull(it, "profile:unit_lock"))
                // Editing turns the exact restoration into a manual edit; undoing brings it back.
                type(it, "profile:value:carb_ratio", "1")
                assertEquals(s(R.string.profile_editor_origin_manual_from, 1), text(it, "profile:editor:origin"))
                type(it, "profile:value:carb_ratio", "0")
                assertEquals(s(R.string.profile_editor_origin_restored, 1), text(it, "profile:editor:origin"))
            }
            assertEquals(2, stored().size)
            click(scenario, "profile:save")
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 3))
            awaitView(scenario, "profile:current:origin", s(R.string.profile_origin_restored, 1))
        }
        val v3 = stored().last()
        assertEquals(ProfileOrigin.RESTORED, v3.origin)
        assertEquals(1L, v3.restoredFrom)
        assertEquals(v1.content, v3.content)
        assertEquals(v1.contentSha256, v3.contentSha256)
    }

    @Test fun pendingEditorIsOnlyDiscardedAfterExplicitConfirmation() {
        seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), ProfileWrite(1, content(ratio = entered("12")), ProfileOrigin.MANUAL, null))
        launch().use { scenario ->
            openProfile(scenario)
            awaitView(scenario, "profile:edit")
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:value:carb_ratio")
            scenario.onActivity {
                type(it, "profile:value:carb_ratio", "14")
                view(it, "profile:history").performClick()
            }
            awaitView(scenario, "profile:history:1:restore")
            click(scenario, "profile:history:1:restore")
            awaitView(scenario, "profile:restore:question",
                s(R.string.profile_restore_question, 1, 3) + "\n" + s(R.string.profile_restore_discard_warning))
            scenario.onActivity { assertEquals(s(R.string.profile_restore_confirm_discard, 1), text(it, "profile:restore:confirm")) }
            click(scenario, "profile:restore:cancel")
            click(scenario, "profile:history:close")
            awaitView(scenario, "profile:value:carb_ratio", "14")
            scenario.onActivity { view(it, "profile:history").performClick() }
            awaitView(scenario, "profile:history:1:restore")
            click(scenario, "profile:history:1:restore")
            awaitView(scenario, "profile:restore:confirm")
            click(scenario, "profile:restore:confirm")
            awaitView(scenario, "profile:value:carb_ratio", "10")
        }
        assertEquals(2, stored().size)
    }

    @Test fun restoredEditorCannotSaveWhenTheStoredHistoryFailsToRead() {
        seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null), ProfileWrite(1, content(ratio = entered("12")), ProfileOrigin.MANUAL, null))
        lateinit var snapshot: android.os.Bundle
        val first = SqliteClinicalProfileRepository(context, name)
        lateinit var model: ClinicalProfileModel
        instrumentation.runOnMainSync {
            model = ClinicalProfileModel(ClinicalProfiles(first, ProfileClock { 0 }, "test/restore", AndroidTimeZoneRules), first, null)
            model.ensureLoaded()
        }
        awaitHistory(model)
        instrumentation.runOnMainSync {
            model.startEdit(); model.setInput(ProfileParameter.CARB_RATIO.code, "13")
            assertTrue(model.canSave)
            snapshot = model.snapshot()
            model.dispose()
        }
        // Damage an older version, then recreate the editor from saved state as after process death.
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER profile_segments_immutable_update")
            db.execSQL("UPDATE profile_segments SET value = '9' WHERE version = 1 AND parameter = 'carb_ratio'")
        }
        val second = SqliteClinicalProfileRepository(context, name)
        lateinit var restored: ClinicalProfileModel
        instrumentation.runOnMainSync {
            restored = ClinicalProfileModel(ClinicalProfiles(second, ProfileClock { 0 }, "test/restore", AndroidTimeZoneRules),
                second, snapshot)
            assertNotNull(restored.editor)
            // Nothing is proven yet: no save while the read is pending.
            assertFalse(restored.canSave)
            restored.ensureLoaded()
        }
        val limit = SystemClock.uptimeMillis() + 5_000
        var state: ProfileHistory? = null
        while (state == null && SystemClock.uptimeMillis() < limit) {
            instrumentation.runOnMainSync { state = restored.history }
            if (state == null) SystemClock.sleep(20)
        }
        try {
            assertEquals(ProfileHistory.Failed(ProfileFailure.INVALID_RECORD), state)
            instrumentation.runOnMainSync {
                assertFalse(restored.canSave)
                restored.save()
                assertFalse(restored.busy)
            }
            // Even bypassing the model, storage refuses to append to the damaged history.
            val editor = restored.editor!!
            assertEquals(ProfileSave.Failed(ProfileFailure.INVALID_RECORD),
                ClinicalProfiles(second, ProfileClock { 0 }, "test/restore", AndroidTimeZoneRules).save(editor))
        } finally { instrumentation.runOnMainSync { restored.dispose() } }
        assertEquals(2L, storedVersionCount(name))
    }

    private fun storedVersionCount(file: String): Long =
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            android.database.DatabaseUtils.longForQuery(it, "SELECT count(*) FROM profile_versions", null)
        }

    @Test fun multiSegmentVersionIsShownCompletelyAndNotEditable() {
        val split = ProfileContent(1, mgdl, madrid, listOf(
            ParameterSchedule(ProfileParameter.CARB_RATIO, listOf(TimeSegment(0, 360, entered("8")),
                TimeSegment(360, 1440, ProfileValue.NotConfigured))),
            ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
            ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET)))
        seed(ProfileWrite(0, split, ProfileOrigin.MANUAL, null))
        launch().use { scenario ->
            openProfile(scenario)
            val segments = s(R.string.profile_segment, "00:00", "06:00", "8 g/U") + "\n" +
                s(R.string.profile_segment, "06:00", "24:00", s(R.string.profile_not_configured))
            awaitView(scenario, "profile:current:carb_ratio",
                s(R.string.profile_param_value, s(R.string.profile_param_carb_ratio), segments))
            click(scenario, "profile:edit")
            awaitView(scenario, "profile:segments:carb_ratio")
            scenario.onActivity {
                assertNull(viewOrNull(it, "profile:value:carb_ratio"))
                type(it, "profile:value:insulin_sensitivity", "41")
                view(it, "profile:save").performClick()
            }
            awaitView(scenario, "profile:saved", s(R.string.profile_saved, 2))
        }
        assertEquals(split.schedule(ProfileParameter.CARB_RATIO), stored().last().content.schedule(ProfileParameter.CARB_RATIO))
    }

    @Test fun bolusStaysBlockedAndDoesNotUseTheSavedProfile() {
        seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null))
        launch().use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation).findViewWithTag<View>("tab:/bolus").performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(view(activity, "blocked:calculate").isEnabled)
                assertFalse(view(activity, "blocked:confirm").isEnabled)
                val views = mutableListOf<View>()
                fun walk(root: View) {
                    views.add(root)
                    if (root is android.view.ViewGroup) (0 until root.childCount).forEach { walk(root.getChildAt(it)) }
                }
                walk(activity.findViewById(R.id.screen_content))
                // Bolo still reports the profile as unavailable and renders nothing from the saved version.
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.profile_absent) })
                assertFalse(views.any { (it.tag as? String)?.startsWith("profile:") == true })
                assertTrue(text(activity, "blocking_codes").contains("profile"))
            }
        }
    }

    private fun assertFits(activity: MainActivity, model: ClinicalProfileModel, expectedTag: String) {
        fun descendants(root: View): List<View> = listOf(root) + if (root is android.view.ViewGroup)
            (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
        // Representative expanded portrait/landscape viewports, not a claim about hardware pixels.
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
                val panel = shell.findViewById<android.widget.LinearLayout>(R.id.screen_content)
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
            }
        }
    }

    private fun awaitHistory(model: ClinicalProfileModel) {
        val limit = SystemClock.uptimeMillis() + 5_000
        var loaded = false
        while (!loaded && SystemClock.uptimeMillis() < limit) {
            instrumentation.runOnMainSync { loaded = model.history is ProfileHistory.Loaded }
            if (!loaded) SystemClock.sleep(20)
        }
        assertTrue(loaded)
    }

    @Test fun editorAndRestoreFitExpandedLayoutsAtLargeFontInBothThemes() {
        seed(ProfileWrite(0, content(), ProfileOrigin.MANUAL, null),
            ProfileWrite(1, content(unit = mmol, sensitivity = ProfileValue.NotConfigured, target = ProfileValue.NotConfigured),
                ProfileOrigin.MANUAL, null))
        val repository = SqliteClinicalProfileRepository(context, name)
        lateinit var model: ClinicalProfileModel
        instrumentation.runOnMainSync {
            model = ClinicalProfileModel(ClinicalProfiles(repository, ProfileClock { 0 }, "test/layout", AndroidTimeZoneRules),
                repository, null)
            model.ensureLoaded()
        }
        try {
            awaitHistory(model)
            launch().use { scenario ->
                // Editor with the unit-change notice open (latest is mmol/L, switching back to mg/dL).
                scenario.onActivity {
                    model.startEdit(); model.setUnit(mgdl)
                    assertTrue(model.editor!!.glucoseDependentValuesLocked)
                    assertFits(it, model, "profile:unit_lock")
                }
                instrumentation.runOnMainSync { model.closeEditor(); model.openHistory() }
                awaitHistory(model)
                scenario.onActivity {
                    model.proposeRestore(1)
                    assertFits(it, model, "profile:restore:question")
                }
            }
        } finally { instrumentation.runOnMainSync { model.dispose() } }
    }
}
