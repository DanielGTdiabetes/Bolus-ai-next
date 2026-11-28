package org.bolusai.next.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.mfp.MfpDate
import org.bolusai.mfp.MfpDiaryInterpreter
import org.bolusai.mfp.MfpDiaryReader
import org.bolusai.mfp.MfpMealId
import org.bolusai.mfp.MfpPause
import org.bolusai.mfp.MfpSha256
import org.bolusai.mfp.MfpStableReading
import org.bolusai.mfp.ReadStableMfpDiary
import org.bolusai.next.application.MfpImportOutcome
import org.bolusai.next.mfp.MfpLinkSwitch
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A stable reading with the instant it finished. Kept in memory only, and only while its screen is open. */
internal data class MfpReadingView(val reading: MfpStableReading, val readAtEpochMs: Long)

/**
 * State of the MyFitnessPal screen (ADR 0032, second delivery). A reading starts only from an explicit action of the
 * user, is never stored and is dropped when the screen is left, so no earlier reading can stand in for a current one.
 */
internal class MfpLinkModel(
    private val switch: MfpLinkSwitch,
    private val clock: () -> Long,
    today: String,
    private val pause: MfpPause = MfpPause { Thread.sleep(it) },
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private val interpreter = MfpDiaryInterpreter(MfpSha256 { text ->
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    })
    private var request = 0L

    var changed: (() -> Unit)? = null
    val enabled: Boolean get() = switch.enabled

    /** Day to read, as typed. It starts as the phone's current day and is never corrected silently. */
    var dateText: String = today
    var reading: Boolean = false
        private set
    var result: MfpReadingView? = null
        private set

    /** A local reason the last action did not run, for example a date that is not a day. Stable code. */
    var notice: String? = null
        private set
    val imports = mutableMapOf<MfpMealId, MfpImportOutcome>()
    val importing = mutableSetOf<MfpMealId>()

    /** Turning the link off also drops what was read. Returns false when the choice could not be stored. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!switch.set(enabled)) {
            notice = SWITCH_NOT_STORED
            return false
        }
        notice = null
        if (!enabled) leave()
        return true
    }

    /** Reads the diary until two loads agree, on a worker thread. [reader] is not kept after the reading. */
    fun read(reader: MfpDiaryReader) {
        if (!enabled || reading || disposed.get()) return
        val date = MfpDate.parse(dateText.trim())
        result = null
        imports.clear()
        if (date == null) {
            notice = DATE_INVALID
            changed?.invoke()
            return
        }
        notice = null
        reading = true
        val current = ++request
        changed?.invoke()
        worker.execute {
            val stable = ReadStableMfpDiary(reader, pause, interpreter).read(date)
            val readAt = clock()
            main.post {
                if (disposed.get() || current != request) return@post
                reading = false
                result = MfpReadingView(stable, readAt)
                changed?.invoke()
            }
        }
    }

    /** Forgets the reading in memory and any reading in progress. Nothing saved in the drafts is touched. */
    fun leave() {
        ++request
        reading = false
        result = null
        imports.clear()
        importing.clear()
    }

    override fun onCleared() {
        changed = null
        if (disposed.compareAndSet(false, true)) worker.shutdown()
    }

    companion object {
        const val DATE_INVALID = "mfp.link.date_invalid"
        const val SWITCH_NOT_STORED = "mfp.link.switch_not_stored"
    }
}
