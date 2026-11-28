package org.bolusai.next.mfp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.mfp.MfpAmount
import org.bolusai.mfp.MfpDate
import org.bolusai.mfp.MfpDiaryInterpreter
import org.bolusai.mfp.MfpDiaryReading
import org.bolusai.mfp.MfpFailure
import org.bolusai.mfp.MfpPageLoad
import org.bolusai.mfp.MfpSha256
import org.bolusai.mfp.MfpUnknown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * ADR 0032, second delivery: the real web view runs the extraction script on a synthetic page. Every request is
 * answered by the test: nothing reaches MyFitnessPal or any network, and the app's own session is not touched.
 */
@RunWith(AndroidJUnit4::class)
class MfpWebSessionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val date = requireNotNull(MfpDate.parse(SyntheticMfpDiary.DATE))
    private val interpreter = MfpDiaryInterpreter(MfpSha256 { text ->
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    })
    private var session: MfpWebSession? = null

    private fun session(answers: SyntheticMfpDiary.Answers): MfpWebSession {
        instrumentation.runOnMainSync { session = MfpWebSession(context, answers) }
        return requireNotNull(session)
    }

    @After fun destroy() {
        instrumentation.runOnMainSync { session?.destroy() }
    }

    @Test fun readsTheDiaryTableAsTheSharedModuleExpectsIt() {
        val answers = SyntheticMfpDiary.Answers()

        val loaded = session(answers).read(date) as MfpPageLoad.Loaded

        assertEquals(SyntheticMfpDiary.URL, loaded.page.finalUrl)
        assertEquals("Synthetic diary", loaded.page.title)
        assertFalse(loaded.page.challengeMarker || loaded.page.lockedDiaryMarker)
        assertEquals(listOf("Breakfast", "Calories", "Carbs", "Fat", "Protein", "Fiber"),
            loaded.page.meals[0].header.map { it.trim() })
        val diary = interpreter.interpret(date, loaded.page) as MfpDiaryReading.Diary
        val breakfast = diary.meals.single()
        assertEquals("mfp:2026-01-15:breakfast", breakfast.id.text)
        assertEquals(listOf("Synthetic oats, 40 g", "Ración sintética, 1 ración"), breakfast.foods.map { it.name })
        assertEquals("ración", breakfast.foods[1].unit)
        assertEquals(MfpAmount.Known(36_600), breakfast.totals.carbohydrates)
        assertEquals(MfpAmount.Known(5_500), breakfast.totals.fat)
        assertEquals(MfpAmount.Known(11_200), breakfast.totals.protein)
        assertEquals(MfpAmount.Known(1_384_000), breakfast.totals.calories)
        assertEquals(MfpAmount.Unknown(MfpUnknown.FOOD_UNKNOWN), breakfast.totals.fiber)
    }

    @Test fun aThirdPartyRequestNeverLeavesAndIsListed() {
        val answers = SyntheticMfpDiary.Answers()
        val session = session(answers)

        session.read(date)

        assertTrue(session.blockedHosts.toString(), "tracker.example.org" in session.blockedHosts)
        assertTrue(answers.asked.toString(), answers.asked.none { "example.org" in it })
        assertTrue(answers.asked.toString(), answers.asked.all { MfpHosts.resourceAllowed(it) })
    }

    @Test fun aPageThatIsNotTheDiaryHasNoMeals() {
        val answers = SyntheticMfpDiary.Answers(body = "<html><head><title>Log In</title></head><body><form></form></body></html>")

        val loaded = session(answers).read(date) as MfpPageLoad.Loaded

        assertTrue(loaded.page.meals.isEmpty())
        assertEquals(MfpDiaryReading.Failed(MfpFailure.UNEXPECTED_PAGE), interpreter.interpret(date, loaded.page))
    }

    @Test fun aRejectedSessionIsReportedAsSuchNotAsOffline() {
        val answers = SyntheticMfpDiary.Answers(status = 403, body = "<html><head><title>Denied</title></head><body></body></html>")

        val loaded = session(answers).read(date) as MfpPageLoad.Loaded

        assertEquals(403, loaded.page.httpStatus)
        assertEquals(MfpDiaryReading.Failed(MfpFailure.SESSION_REJECTED), interpreter.interpret(date, loaded.page))
    }

    @Test fun aSecondReadIsANewLoad() {
        val answers = SyntheticMfpDiary.Answers()
        val session = session(answers)

        session.read(date)
        session.read(date)

        assertEquals(2, answers.asked.count { it == SyntheticMfpDiary.URL })
    }

    @Test fun anAddressOutsideMyFitnessPalIsNotOpened() {
        val answers = SyntheticMfpDiary.Answers()
        val session = session(answers)

        instrumentation.runOnMainSync { session.open("https://example.org/") }
        instrumentation.waitForIdleSync()

        assertEquals(listOf("example.org"), session.blockedHosts)
        assertTrue(answers.asked.isEmpty())
    }

    @Test fun instrumentationCannotBuildASessionThatReachesTheNetworkNorSignOut() {
        instrumentation.runOnMainSync {
            assertThrows(IllegalStateException::class.java) { MfpWebSession(context) }
        }
        val session = session(SyntheticMfpDiary.Answers())
        instrumentation.runOnMainSync {
            assertThrows(IllegalStateException::class.java) { session.signOut { } }
        }
    }
}
