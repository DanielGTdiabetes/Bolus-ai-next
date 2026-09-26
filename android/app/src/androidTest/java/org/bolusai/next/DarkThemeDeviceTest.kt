package org.bolusai.next

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.meals.*
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.meals.SqliteMealRepository
import org.bolusai.next.navigation.Destination
import org.bolusai.next.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Night contexts only: never changes the phone's display settings or reads its normal library. */
@RunWith(AndroidJUnit4::class)
class DarkThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun themed(base: Context, night: Boolean, scale: Float = 1f): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = scale
        }
        return ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_BolusAiNext)
    }

    @Test fun dayAndNightPalettesKeepTextReadableAndNativeControlsUseTheMatchingTheme() {
        val pairs = listOf(
            R.color.primary_text to R.color.page_background,
            R.color.primary_text to R.color.status_background,
            R.color.secondary_text to R.color.page_background,
            R.color.secondary_text to R.color.status_background,
            R.color.error_text to R.color.status_background,
            R.color.non_authoritative_text to R.color.non_authoritative_background,
            R.color.primary_text to R.color.non_authoritative_background,
            R.color.accent to R.color.page_background,
            R.color.accent to R.color.status_background,
            R.color.accent to R.color.accent_surface,
            R.color.on_hero to R.color.hero_start,
            R.color.on_hero to R.color.hero_end,
        )
        for (night in listOf(false, true)) {
            val context = themed(instrumentation.targetContext, night)
            for ((foreground, background) in pairs) {
                val ratio = ColorUtils.calculateContrast(context.getColor(foreground), context.getColor(background))
                assertTrue("Insufficient text contrast: night=$night foreground=$foreground background=$background ratio=$ratio", ratio >= 4.5)
            }
            fun booleanAttribute(@androidx.annotation.AttrRes attribute: Int): Boolean {
                val value = android.util.TypedValue()
                assertTrue(context.theme.resolveAttribute(attribute, value, true))
                assertEquals(android.util.TypedValue.TYPE_INT_BOOLEAN, value.type)
                return value.data != 0
            }
            assertEquals(!night, booleanAttribute(android.R.attr.windowLightStatusBar))
            if (Build.VERSION.SDK_INT >= 27) assertEquals(!night, booleanAttribute(android.R.attr.windowLightNavigationBar))
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(!night, booleanAttribute(android.R.attr.isLightTheme))
                assertFalse(booleanAttribute(android.R.attr.forceDarkAllowed))
            }
        }
        val day = themed(instrumentation.targetContext, false).getColor(R.color.page_background)
        val night = themed(instrumentation.targetContext, true).getColor(R.color.page_background)
        assertTrue(ColorUtils.calculateLuminance(night) < ColorUtils.calculateLuminance(day))
    }

    @Test fun screensRenderInBothThemesAndFoldWidthsWithoutChangingDraftOrSelection() {
        val record = MealRecord("synthetic-theme", 1, MealKind.DRAFT,
            MealContent(name = draftField("Comida sintética"), carbs = draftField("0")))
        val repository = object : MealRepository, MealSelectionRepository {
            override fun read(kind: MealKind) = MealRead.Loaded(listOf(record))
            override fun save(editor: MealRecord) = MealSave.Failed(MealFailure.SAVE_FAILED)
            override fun readSelection() = MealSelection.Reviewed(record, record)
            override fun select(record: MealRecord) = MealSelection.Reviewed(record, record)
        }
        lateinit var model: MealDraftModel
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            model = MealDraftModel(MealDrafts(repository, MealIds { "synthetic-theme" }), Closeable {}, null,
                ReviewMealSelection(repository))
            model.selectionChanged = { if (model.selection != null) loaded.countDown() }
            model.refreshSelection()
            model.edit(record)
            model.update(record.content.copy(notes = draftField("Texto sin guardar")))
        }
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        try {
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    for (night in listOf(false, true)) for (widthDp in listOf(360, 840)) for (scale in listOf(1f, 1.8f)) {
                        val context = themed(activity, night, scale)
                        for (destination in listOf(Destination.HOME, Destination.BOLUS, Destination.MEALS)) {
                            val shell = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                            shell.findViewById<TextView>(R.id.screen_title).setText(destination.title)
                            val panel = shell.findViewById<LinearLayout>(R.id.screen_content)
                            val renderer = ScreenRenderer(context, panel, {}, {},
                                renderMeals = { MealDraftScreen(context, panel, model) {}.render(it) },
                                renderSelection = { MealSelectionScreen(context, panel, model) {}.render() })
                            renderer.render(destination, ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute(),
                                SettingsSection.NIGHTSCOUT)
                            Destination.primary.forEach { renderer.addTab(shell.findViewById(R.id.bottom_navigation), it,
                                it == destination.tab) {} }
                            val density = context.resources.displayMetrics.density
                            val width = (widthDp * density).toInt()
                            val height = (900 * density).toInt()
                            shell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            shell.layout(0, 0, width, height)
                            assertTrue(shell.findViewById<View>(R.id.screen_scroll).height > 0)
                            descendants(shell).filterIsInstance<TextView>().filter { it.isVisible }.forEach {
                                assertTrue("Clipped text in $destination, night=$night", it.layout.height <=
                                    it.height - it.compoundPaddingTop - it.compoundPaddingBottom)
                                for (line in 0 until it.layout.lineCount) assertEquals(0, it.layout.getEllipsisCount(line))
                            }
                            if (destination == Destination.BOLUS) {
                                assertFalse(panel.findViewWithTag<View>("blocked:calculate").isEnabled)
                                assertFalse(panel.findViewWithTag<View>("blocked:confirm").isEnabled)
                                assertEquals(context.getString(R.string.draft_missing),
                                    panel.findViewWithTag<TextView>("selection:fat").text.toString())
                            }
                            if (night && widthDp == 840 && scale == 1f) {
                                val bitmap = createBitmap(width, height)
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(context.getColor(R.color.page_background))
                                shell.draw(canvas)
                                val file = File(activity.filesDir, "ui-review/dark-${destination.name}.png")
                                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                bitmap.recycle()
                            }
                        }
                    }
                    assertEquals(MealSelection.Reviewed(record, record), model.selection)
                    assertTrue(model.dirty)
                    assertEquals(draftField("Texto sin guardar"), model.editor!!.content.notes)
                }
            }
        } finally {
            MainActivity.mealRepositoryFactory = null
            instrumentation.runOnMainSync { model.dispose() }
        }
    }
}
