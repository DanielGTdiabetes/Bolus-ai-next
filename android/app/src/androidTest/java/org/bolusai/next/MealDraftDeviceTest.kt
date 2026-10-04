package org.bolusai.next

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
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

    /** Opening Bolo now reads the profile (ADR 0017): never the app's own clinical-profile.db. */
    @org.junit.Before fun isolateProfile() {
        MainActivity.profileRepositoryFactory = { org.bolusai.next.profile.SqliteClinicalProfileRepository(it, null) }
    }

    @After fun clearFactory() {
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
    }

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

    private fun awaitEditorLayout(scenario: ActivityScenario<MainActivity>, input: EditText) {
        val drawn = java.util.concurrent.CountDownLatch(1)
        lateinit var root: View
        lateinit var listener: android.view.ViewTreeObserver.OnPreDrawListener
        scenario.onActivity { activity ->
            root = activity.findViewById(R.id.app_shell)
            val panel = activity.findViewById<View>(R.id.screen_scroll)
            listener = android.view.ViewTreeObserver.OnPreDrawListener {
                if (input.isLaidOut && !input.isLayoutRequested && input.height > 0 &&
                    panel.isLaidOut && !panel.isLayoutRequested && panel.height > 0) {
                    root.viewTreeObserver.removeOnPreDrawListener(listener)
                    root.post { drawn.countDown() }
                } else root.postInvalidateOnAnimation()
                true
            }
            root.viewTreeObserver.addOnPreDrawListener(listener)
            root.requestLayout()
            root.postInvalidateOnAnimation()
        }
        val completed = drawn.await(5, java.util.concurrent.TimeUnit.SECONDS)
        scenario.onActivity { root.viewTreeObserver.removeOnPreDrawListener(listener) }
        assertTrue("Synthetic editor did not lay out before measuring scroll", completed)
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

    @Test fun removingCurrentAndObsoleteSelectionSurvivesRelaunchAndAllowsExplicitReselection() {
        val context = instrumentation.targetContext
        for (obsolete in listOf(false, true)) {
            val name = "synthetic-clear-ui-${UUID.randomUUID()}.db"
            val latest = SqliteMealRepository(context, name).use {
                val record = (it.save(MealRecord("clear-ui", 0, MealKind.DRAFT,
                    MealContent(carbs = draftField("0")))) as MealSave.Saved).record
                it.select(record)
                if (obsolete) (it.save(record.copy(content = MealContent(carbs = draftField("nuevo")))) as MealSave.Saved).record
                else record
            }
            MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
            try {
                launch().use { scenario ->
                    scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                        .findViewWithTag<View>("tab:/bolus").performClick() }
                    awaitView(scenario, "selection:block", if (obsolete) "meal.selection.obsolete" else "meal.draft.not_clinically_validated")
                    scenario.onActivity { view(it, "selection:clear").performClick() }
                    awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                    scenario.onActivity {
                        assertNull(viewOrNull(it, "selection:carbs"))
                        assertNull(viewOrNull(it, "selection:clear"))
                        assertFalse(view(it, "blocked:calculate").isEnabled)
                        assertFalse(view(it, "blocked:confirm").isEnabled)
                    }
                    scenario.recreate()
                    awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                }
                launch().use { scenario ->
                    scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                        .findViewWithTag<View>("tab:/bolus").performClick() }
                    awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                    scenario.onActivity { view(it, "link:native/meals").performClick() }
                    awaitView(scenario, "meal:select:clear-ui")
                    scenario.onActivity { view(it, "meal:select:clear-ui").performClick() }
                    awaitView(scenario, "selection:carbs", if (obsolete) "nuevo" else "0")
                    scenario.onActivity {
                        assertFalse(view(it, "blocked:calculate").isEnabled)
                        assertFalse(view(it, "blocked:confirm").isEnabled)
                    }
                }
                SqliteMealRepository(context, name).use {
                    assertEquals(MealSelection.Reviewed(latest, latest), it.readSelection())
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun failedRemovalShowsFailureUntilReadAndExplicitRetry() {
        val context = instrumentation.targetContext
        val name = "synthetic-clear-failure-ui-${UUID.randomUUID()}.db"
        val record = SqliteMealRepository(context, name).use {
            val saved = (it.save(MealRecord("clear-failure-ui", 0, MealKind.DISH,
                MealContent(carbs = draftField("0")))) as MealSave.Saved).record
            it.select(saved)
            saved
        }
        fun execute(sql: String) = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL(sql)
        }
        execute("CREATE TRIGGER fail_clear AFTER DELETE ON meal_selection BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
        try {
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                    .findViewWithTag<View>("tab:/bolus").performClick() }
                awaitView(scenario, "selection:clear")
                scenario.onActivity { view(it, "selection:clear").performClick() }
                awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_failed, MealFailure.SAVE_FAILED.code))
                scenario.onActivity {
                    assertNull(viewOrNull(it, "selection:carbs"))
                    assertFalse(view(it, "blocked:calculate").isEnabled)
                    assertFalse(view(it, "blocked:confirm").isEnabled)
                }
                SqliteMealRepository(context, name).use {
                    assertEquals(MealSelection.Reviewed(record, record), it.readSelection())
                }
                execute("DROP TRIGGER fail_clear")
                scenario.onActivity { view(it, "selection:retry").performClick() }
                awaitView(scenario, "selection:carbs", "0")
                scenario.onActivity { view(it, "selection:clear").performClick() }
                awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pendingReadCannotRestoreSelectionWhileRemovalWaitsForCommit() {
        val readStarted = java.util.concurrent.CountDownLatch(1)
        val releaseRead = java.util.concurrent.CountDownLatch(1)
        val clearStarted = java.util.concurrent.CountDownLatch(1)
        val releaseClear = java.util.concurrent.CountDownLatch(1)
        val cleared = java.util.concurrent.CountDownLatch(1)
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val record = MealRecord("pending-read", 1, MealKind.DRAFT, MealContent())
        val repository = object : MealRepository, MealSelectionRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(emptyList())
            override fun save(editor: MealRecord) = MealSave.Failed(MealFailure.SAVE_FAILED)
            override fun select(record: MealRecord): MealSelection = error("Cannot select during removal")
            override fun readSelection(): MealSelection {
                readStarted.countDown()
                check(releaseRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MealSelection.Reviewed(record, record)
            }
            override fun clearSelection(): MealSelection {
                clearCalls.incrementAndGet()
                clearStarted.countDown()
                check(releaseClear.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MealSelection.Missing
            }
        }
        lateinit var model: MealDraftModel
        instrumentation.runOnMainSync {
            model = MealDraftModel(MealDrafts(repository, MealIds { "pending-read" }), Closeable {}, null,
                ReviewMealSelection(repository))
            model.changed = { if (model.selection == MealSelection.Missing) cleared.countDown() }
            model.refreshSelection()
        }
        try {
            assertTrue(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                model.clearSelection()
                model.clearSelection()
                model.select(record)
                model.refreshSelection()
                assertTrue(model.busy)
                assertNull(model.selection)
            }
            releaseRead.countDown()
            assertTrue(clearStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNull(model.selection)
                assertTrue(model.busy)
            }
            releaseClear.countDown()
            assertTrue(cleared.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(MealSelection.Missing, model.selection)
                assertFalse(model.busy)
                assertFalse(requireNotNull(model.selection).allowsCalculation)
                assertFalse(requireNotNull(model.selection).allowsTreatment)
            }
            assertEquals(1, clearCalls.get())
        } finally {
            releaseRead.countDown(); releaseClear.countDown()
            instrumentation.runOnMainSync { model.dispose() }
        }
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
            override fun clearSelection() = MealSelection.Missing
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

    @Test fun resumingMealAndDishEditorsPreservesUnsavedTextFocusAndScroll() {
        val context = instrumentation.targetContext
        for (kind in MealKind.entries) {
            val name = "synthetic-resume-${UUID.randomUUID()}.db"
            SqliteMealRepository(context, name).use {
                it.save(MealRecord("resume-editor", 0, kind, MealContent()))
            }
            MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, name) }
            try {
                launch().use { scenario ->
                    scenario.onActivity { it.findViewById<View>(R.id.bottom_navigation)
                        .findViewWithTag<View>("tab:/bolus").performClick() }
                    awaitView(scenario, "selection:status", context.getString(R.string.meal_selection_missing))
                    scenario.onActivity { view(it, if (kind == MealKind.DISH) "link:/favorites" else "link:native/meals").performClick() }
                    awaitView(scenario, "meal:edit:resume-editor")
                    scenario.onActivity { view(it, "meal:edit:resume-editor").performClick() }
                    lateinit var input: EditText
                    var scroll = 0
                    var before = ""
                    fun geometry(activity: MainActivity): String {
                        val panel = activity.findViewById<View>(R.id.screen_scroll)
                        val imeVisible = if (android.os.Build.VERSION.SDK_INT >= 30)
                            panel.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) else null
                        return "panel=${panel.height}, field=${input.top}/${input.height}, ime=$imeVisible"
                    }
                    scenario.onActivity { activity ->
                        input = view(activity, "meal:notes") as EditText
                        input.append("Texto sintético sin guardar")
                        assertTrue(input.requestFocus())
                        input.setSelection(4)
                    }
                    awaitEditorLayout(scenario, input)
                    scenario.onActivity { activity ->
                        // Keep the focused field visible so Android need not scroll it back into view on resume.
                        val panel = activity.findViewById<android.widget.ScrollView>(R.id.screen_scroll)
                        panel.scrollTo(0, (input.top - (panel.height - input.height) / 2).coerceAtLeast(0))
                    }
                    awaitEditorLayout(scenario, input)
                    scenario.onActivity { activity ->
                        scroll = activity.findViewById<android.widget.ScrollView>(R.id.screen_scroll).scrollY
                        before = geometry(activity)
                        assertTrue(scroll > 0)
                    }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    awaitEditorLayout(scenario, input)
                    scenario.onActivity { activity ->
                        assertSame(input, view(activity, "meal:notes"))
                        assertEquals("Texto sintético sin guardar", input.text.toString())
                        assertTrue(input.hasFocus())
                        assertEquals(4, input.selectionStart)
                        assertEquals("Before: $before; after: ${geometry(activity)}", scroll,
                            activity.findViewById<android.widget.ScrollView>(R.id.screen_scroll).scrollY)
                        assertTrue(view(activity, "meal:save").isEnabled)
                    }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun unfoldedLayoutsShowSelectionAndEditorWithoutClippingAtLargeFont() {
        val record = MealRecord("synthetic-fold-selection", 1, MealKind.DRAFT,
            MealContent(name = draftField("Plato sintético para revisar en pantalla interior"),
                carbs = draftField("0"), protein = draftField("sin validar")),
            DishReference("synthetic-source", 3))
        val repository = object : MealRepository, MealSelectionRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(listOf(record).filter { it.kind == kind })
            override fun save(editor: MealRecord) = MealSave.Failed(MealFailure.SAVE_FAILED)
            override fun readSelection() = MealSelection.Reviewed(record, record)
            override fun select(record: MealRecord) = MealSelection.Reviewed(record, record)
            override fun clearSelection() = MealSelection.Missing
        }
        lateinit var model: MealDraftModel
        val loaded = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            model = MealDraftModel(MealDrafts(repository, MealIds { "synthetic-fold" }), Closeable {}, null,
                ReviewMealSelection(repository))
            model.selectionChanged = { if (model.selection != null) loaded.countDown() }
            model.refreshSelection()
        }
        try {
            assertTrue(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
            launch().use { scenario ->
                scenario.onActivity { activity ->
                    fun descendants(root: View): List<View> = listOf(root) + if (root is android.view.ViewGroup)
                        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
                    // Representative expanded portrait/landscape viewports, not a claim about hardware pixels.
                    for ((widthDp, heightDp) in listOf(840 to 900, 900 to 840)) {
                        for (scale in listOf(1f, 1.8f)) {
                            val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                                screenWidthDp = widthDp; screenHeightDp = heightDp; fontScale = scale
                            }
                            val context = activity.createConfigurationContext(configuration)
                            for (editor in listOf(false, true)) {
                                val shell = android.view.LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                                val panel = shell.findViewById<android.widget.LinearLayout>(R.id.screen_content)
                                val renderer = org.bolusai.next.ui.ScreenRenderer(context, panel, {}, {},
                                    renderSelection = { org.bolusai.next.ui.MealSelectionScreen(context, panel, model) {}.render() })
                                if (editor) {
                                    model.edit(record)
                                    shell.findViewById<TextView>(R.id.screen_title).setText(R.string.draft_editor)
                                    MealDraftScreen(context, panel, model) {}.render(MealKind.DRAFT)
                                } else {
                                    shell.findViewById<TextView>(R.id.screen_title).setText(R.string.bolus)
                                    renderer.render(org.bolusai.next.navigation.Destination.BOLUS,
                                        org.bolusai.next.application.ReadOverview(org.bolusai.next.glucose.ReadLocalGlucoseStatus(
                                            org.bolusai.next.glucose.PendingDexcomSource)).execute(),
                                        org.bolusai.next.ui.SettingsSection.NIGHTSCOUT,
                                        // Synthetic E1 lines: this layout check never reads a profile (ADR 0017).
                                        org.bolusai.next.ui.BlockingView(PENDING_LINES, false) {})
                                }
                                org.bolusai.next.navigation.Destination.primary.forEach {
                                    renderer.addTab(shell.findViewById(R.id.bottom_navigation), it,
                                        it == org.bolusai.next.navigation.Destination.BOLUS) {}
                                }
                                val density = context.resources.displayMetrics.density
                                val width = (widthDp * density).toInt()
                                val height = (heightDp * density).toInt()
                                shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                                shell.layout(0, 0, width, height)
                                assertTrue(shell.findViewById<View>(R.id.screen_scroll).height > 0)
                                assertTrue(shell.findViewById<View>(R.id.bottom_navigation).bottom <= height)
                                descendants(shell).filterIsInstance<TextView>().filter { it.isVisible }.forEach {
                                    assertTrue("No width: ${it.tag}", it.width > 0)
                                    assertTrue("Clipped text: ${it.tag}", it.layout.height <=
                                        it.height - it.compoundPaddingTop - it.compoundPaddingBottom)
                                    for (line in 0 until it.layout.lineCount) assertEquals(0, it.layout.getEllipsisCount(line))
                                }
                                if (!editor) {
                                    assertFalse(panel.findViewWithTag<View>("blocked:calculate").isEnabled)
                                    assertFalse(panel.findViewWithTag<View>("blocked:confirm").isEnabled)
                                    assertEquals("0", panel.findViewWithTag<TextView>("selection:carbs").text.toString())
                                }
                                if (widthDp == 840) {
                                    val bitmap = createBitmap(width, height)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    canvas.drawColor(context.getColor(R.color.page_background))
                                    shell.draw(canvas)
                                    val file = java.io.File(activity.filesDir, "ui-review/fold-${if (editor) "editor" else "bolus"}-$scale.png")
                                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                                    file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                    assertEquals(MealSelection.Reviewed(record, record), model.selection)
                }
            }
        } finally { instrumentation.runOnMainSync { model.dispose() } }
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

    private companion object {
        val PENDING_LINES = listOf("input.glucose.policy_not_approved", "input.iob.unknown", "input.meal.missing",
            "input.profile.policy_not_approved [profile.not_approved_for_calculation]",
            "input.profile.unknown [profile.read.pending]")
    }
}
