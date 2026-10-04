package org.bolusai.next

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.next.application.ReadOverview
import org.bolusai.next.glucose.PendingDexcomSource
import org.bolusai.next.glucose.ReadLocalGlucoseStatus
import org.bolusai.next.navigation.Destination
import org.bolusai.next.ui.ScreenRenderer
import org.bolusai.next.ui.SettingsSection
import org.bolusai.next.meals.SqliteMealRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only Next's own view tree is read or captured. No device screenshots or logcat. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun launch(): ActivityScenario<MainActivity> {
        MainActivity.mealRepositoryFactory = { SqliteMealRepository(it, null) }
        // Settings → Cálculo reads the clinical profile; keep it in memory, never the app's file.
        MainActivity.profileRepositoryFactory = { org.bolusai.next.profile.SqliteClinicalProfileRepository(it, null) }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                // Test window only: no device settings, keyguard dismissal or production behavior change.
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                }
            }
            awaitLayout(scenario)
        }
    }

    @After fun clearTestStorageFactory() {
        MainActivity.mealRepositoryFactory = null
        MainActivity.profileRepositoryFactory = null
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
    } else emptyList()

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>) {
        val drawn = CountDownLatch(1)
        var pendingRoot: View? = null
        var pendingListener: ViewTreeObserver.OnPreDrawListener? = null
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.app_shell).apply {
                val root = this
                var stableFrames = 0
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        val ready = views(root).filter { it.isShown }.all {
                            it.isLaidOut && !it.isLayoutRequested && it.width > 0 && it.height > 0
                        }
                        stableFrames = if (ready) stableFrames + 1 else 0
                        if (stableFrames >= 2) {
                            viewTreeObserver.removeOnPreDrawListener(this)
                            // Release after traversal, not from inside a frame that is still drawing.
                            post { drawn.countDown() }
                        } else {
                            postInvalidateOnAnimation()
                        }
                        return true
                    }
                }
                pendingRoot = root
                pendingListener = listener
                viewTreeObserver.addOnPreDrawListener(listener)
                postInvalidateOnAnimation()
            }
        }
        val completed = drawn.await(5, TimeUnit.SECONDS)
        scenario.onActivity { pendingRoot?.viewTreeObserver?.removeOnPreDrawListener(pendingListener) }
        assertTrue("Next did not finish laying out and drawing its visible controls", completed)
        instrumentation.waitForIdleSync()
    }

    private fun click(scenario: ActivityScenario<MainActivity>, tag: String) {
        awaitLayout(scenario)
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(R.id.app_shell).findViewWithTag<View>(tag)
            assertNotNull("Missing control $tag", view)
            assertTrue("Disabled navigation $tag", view.isEnabled)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        awaitLayout(scenario)
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(R.id.app_shell).findViewWithTag<View>(tag)
            assertTrue("Navigation not visible $tag", view.getGlobalVisibleRect(Rect()))
            assertTrue(view.performClick())
        }
        awaitLayout(scenario)
    }

    private fun assertRoute(scenario: ActivityScenario<MainActivity>, destination: Destination) {
        awaitLayout(scenario)
        scenario.onActivity { activity ->
            assertEquals(destination.route, activity.findViewById<TextView>(R.id.screen_title).tag)
            val root = activity.findViewById<View>(R.id.app_shell)
            val safety = activity.findViewById<TextView>(R.id.non_authoritative_label)
            assertTrue(safety.getGlobalVisibleRect(Rect()))
            assertEquals(activity.getString(R.string.shell_safety), safety.text.toString())
            val selected = views(activity.findViewById(R.id.bottom_navigation)).filter { it.isSelected }
            assertEquals(listOf("tab:${destination.tab.route}"), selected.map { it.tag })
            views(root).filter { it.tag?.toString()?.startsWith("blocked:") == true }.forEach {
                assertFalse("Operation became enabled: ${it.tag}", it.isEnabled)
                assertFalse("Operation has a click handler: ${it.tag}", it.hasOnClickListeners())
            }
            assertTrue(views(root).none { it is android.widget.EditText })
        }
    }

    @Test fun everyDestinationIsReachableThroughVisibleControlsAndKeepsSafetyBanner() {
        launch().use { scenario ->
            val queue = ArrayDeque<List<String>>()
            Destination.primary.forEach { queue.add(listOf("tab:${it.route}")) }
            val visited = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val path = queue.removeFirst()
                click(scenario, "tab:/")
                path.forEach { click(scenario, it) }
                var route = ""
                var links = emptyList<String>()
                scenario.onActivity { activity ->
                    route = activity.findViewById<TextView>(R.id.screen_title).tag as String
                    links = views(activity.findViewById(R.id.screen_content)).mapNotNull { it.tag as? String }
                        .filter { it.startsWith("link:") }.distinct()
                }
                if (!visited.add(route)) continue
                assertRoute(scenario, Destination.entries.single { it.route == route })
                links.filter { it.removePrefix("link:") !in visited }.forEach { queue.add(path + it) }
            }
            assertEquals(Destination.entries.map { it.route }.toSet(), visited)
        }
    }

    @Test fun calculationEmergencyAndNativeOfflineRoutesCannotCalculateOrConfirm() {
        launch().use { scenario ->
            val paths = listOf(listOf("tab:/bolus"), listOf("tab:/menu", "link:/manual"),
                listOf("tab:/menu", "link:native/mobile", "link:native/bolus"))
            paths.forEach { path ->
                path.forEach { click(scenario, it) }
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.app_shell)
                    listOf("blocked:calculate", "blocked:confirm").forEach { tag ->
                        val button = root.findViewWithTag<Button>(tag)
                        assertFalse(button.isEnabled)
                        assertFalse(button.performClick())
                    }
                }
                click(scenario, "details")
                scenario.onActivity { activity ->
                    val codes = activity.findViewById<View>(R.id.app_shell).findViewWithTag<TextView>("blocking_codes")
                    assertEquals(View.VISIBLE, codes.visibility)
                    assertTrue(codes.text.contains("input.glucose.policy_not_approved"))
                    assertTrue(codes.text.contains("input.iob.unknown"))
                    // ADR 0017: the synthetic in-memory profile has no version, so Bolo shows its real causes.
                    assertTrue(codes.text.contains(
                        "input.profile.policy_not_approved [profile.not_approved_for_calculation]"))
                    assertTrue(codes.text.contains("input.profile.missing [profile.history.missing]"))
                }
            }
        }
    }

    @Test fun bolusLinksToSavedDishesAndManualDraftsWithoutEnablingCalculation() {
        launch().use { scenario ->
            click(scenario, "tab:/bolus")
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.screen_content)
                assertNotNull(root.findViewWithTag<View>("link:/favorites"))
                assertNotNull(root.findViewWithTag<View>("link:native/meals"))
                assertFalse(root.findViewWithTag<Button>("blocked:calculate").isEnabled)
                assertFalse(root.findViewWithTag<Button>("blocked:confirm").isEnabled)
            }
            click(scenario, "link:/favorites")
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.screen_content)
                    .findViewWithTag<Button>("meal:new").isEnabled)
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            instrumentation.waitForIdleSync()
            click(scenario, "link:native/meals")
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.screen_content)
                    .findViewWithTag<Button>("meal:new").isEnabled)
            }
        }
    }

    @Test fun recreationPreservesChildSettingsSelectionAndBackReturnsToCaller() {
        launch().use { scenario ->
            click(scenario, "tab:/menu")
            click(scenario, "link:/settings")
            click(scenario, "settings:CALCULATION")
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertRoute(scenario, Destination.SETTINGS)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.app_shell)
                    .findViewWithTag<View>("settings:CALCULATION").isSelected)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            instrumentation.waitForIdleSync()
            assertRoute(scenario, Destination.MORE)
            scenario.onActivity { it.findViewById<Button>(R.id.back_button).performClick() }
            instrumentation.waitForIdleSync()
            assertRoute(scenario, Destination.HOME)
        }
    }

    @Test fun everySettingsSectionShowsExplicitPendingState() {
        launch().use { scenario ->
            click(scenario, "tab:/menu")
            click(scenario, "link:/settings")
            SettingsSection.entries.forEach { section ->
                click(scenario, "settings:${section.name}")
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.app_shell)
                    assertTrue(root.findViewWithTag<View>("settings:${section.name}").isSelected)
                    assertTrue(views(root).filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(R.string.settings_detail)
                    })
                }
            }
        }
    }

    @Test fun appHasNoInternetPermissionAndColdEntryContainsOnlyUnavailableData() {
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission("android.permission.INTERNET", context.packageName))
        launch().use { scenario ->
            assertRoute(scenario, Destination.HOME)
            scenario.onActivity { activity ->
                val strings = views(activity.findViewById(R.id.screen_content)).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(strings.contains(activity.getString(R.string.glucose_absent)))
                assertEquals(2, strings.count { it == activity.getString(R.string.unknown) })
                assertTrue(strings.contains(activity.getString(R.string.glucose_metadata)))
            }
        }
    }

    @Test fun captureAppOnlyReviewImagesAndVerifyNarrowLargeTextLayout() {
        launch().use { scenario ->
            listOf("home" to "tab:/", "bolus" to "tab:/bolus", "more" to "tab:/menu").forEach { (name, tag) ->
                click(scenario, tag)
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.app_shell)
                    assertTrue(root.width > 0 && root.height > 0)
                    saveImage(root, File(activity.filesDir, "ui-review/$name.png"))
                    val bar = activity.findViewById<LinearLayout>(R.id.bottom_navigation)
                    assertTrue(bar.height > 0)
                    views(bar).filterIsInstance<Button>().forEach {
                        assertTrue("${it.tag}: width=${it.width}, density=${activity.resources.displayMetrics.density}",
                            it.width >= (48 * activity.resources.displayMetrics.density).toInt())
                    }
                }
            }
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.8f }
                val largeContext = activity.createConfigurationContext(configuration)
                val panel = LinearLayout(largeContext).apply { orientation = LinearLayout.VERTICAL }
                ScreenRenderer(largeContext, panel, {}, {}).render(Destination.HOME,
                    ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute(), SettingsSection.NIGHTSCOUT)
                val width = (320 * largeContext.resources.displayMetrics.density).toInt()
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, width, panel.measuredHeight)
                views(panel).filterIsInstance<TextView>().forEach { view ->
                    assertTrue("Text has no width", view.width > 0)
                    val layout = view.layout
                    assertTrue("Text clipped vertically", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals("Text ellipsized", 0, layout.getEllipsisCount(line))
                    }
                }
                saveImage(panel, File(activity.filesDir, "ui-review/home-large-text.png"))
                // Measure complete shells without changing the phone's font or orientation settings.
                listOf(Triple(320, 640, 1.8f), Triple(640, 360, 1f)).forEach { (widthDp, heightDp, scale) ->
                    val config = Configuration(activity.resources.configuration).apply { fontScale = scale }
                    val shellContext = activity.createConfigurationContext(config)
                    val shell = LayoutInflater.from(shellContext).inflate(R.layout.activity_main, null) as LinearLayout
                    shell.findViewById<TextView>(R.id.screen_title).setText(R.string.home)
                    val shellRenderer = ScreenRenderer(shellContext, shell.findViewById(R.id.screen_content), {}, {})
                    shellRenderer.render(Destination.HOME,
                        ReadOverview(ReadLocalGlucoseStatus(PendingDexcomSource)).execute(), SettingsSection.NIGHTSCOUT)
                    Destination.primary.forEach {
                        shellRenderer.addTab(shell.findViewById(R.id.bottom_navigation), it, it == Destination.HOME) {}
                    }
                    val density = shellContext.resources.displayMetrics.density
                    val shellWidth = (widthDp * density).toInt()
                    val shellHeight = (heightDp * density).toInt()
                    shell.measure(View.MeasureSpec.makeMeasureSpec(shellWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(shellHeight, View.MeasureSpec.EXACTLY))
                    shell.layout(0, 0, shellWidth, shellHeight)
                    assertTrue(shell.findViewById<View>(R.id.screen_scroll).height > 0)
                    val navigation = shell.findViewById<LinearLayout>(R.id.bottom_navigation)
                    assertTrue(navigation.bottom <= shellHeight)
                    views(navigation).filterIsInstance<Button>().forEach { tab ->
                        assertTrue(tab.width >= (48 * density).toInt())
                        assertTrue(tab.layout.height <= tab.height - tab.compoundPaddingTop - tab.compoundPaddingBottom)
                        assertEquals("Navigation labels must not split within a word", 2, tab.layout.lineCount)
                    }
                    saveImage(shell, File(activity.filesDir, "ui-review/shell-$widthDp.png"))
                }
            }
        }
    }

    private fun saveImage(view: View, file: File) {
        val bitmap = createBitmap(view.width, view.height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(view.context.getColor(R.color.page_background))
        view.draw(canvas)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
