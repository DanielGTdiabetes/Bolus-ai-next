package org.bolusai.next.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.dexcomg7.MessageProvenance
import org.bolusai.dexcomg7.NoReadingCause
import org.bolusai.dexcomg7.ReadingFreshness
import org.bolusai.dexcomg7.WithheldCause
import org.bolusai.next.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One line of a composed block: a string resource and its arguments, resolved by the renderer. */
internal data class UiText(val id: Int, val args: List<Any> = emptyList())

/**
 * The "Glucosa actual" card. [value] is the number and is present only for a reading that is current at the instant
 * the view was composed (owner decision of 2026-11-28: up to 10 minutes after its measurement). Otherwise [status]
 * names why there is no current glucose and [detail], when present, says when the last measurement was.
 */
internal data class CurrentGlucoseView(val value: UiText?, val status: UiText, val detail: UiText?)

/**
 * What Inicio and Bolo show about the latest Dexcom reading (ADR 0023, C1). [value] is null when no number is shown.
 * [current] feeds the "Glucosa actual" card.
 */
internal data class DexcomReadingView(val value: UiText?, val lines: List<UiText>, val current: CurrentGlucoseView)

/**
 * Pure composition of the presentation (ADR 0023, part II). The number of the technical block appears for every
 * [LatestReadingPresentation.Reading], with its measurement time, its age and whether it is current or expired. The
 * "Glucosa actual" card shows the number only while the reading is current at [nowEpochMs].
 */
internal object DexcomReadingBlock {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun view(presentation: LatestReadingPresentation?, zone: ZoneId, nowEpochMs: Long): DexcomReadingView = when (presentation) {
        null -> DexcomReadingView(null, listOf(UiText(R.string.dexcom_reading_reading)),
            CurrentGlucoseView(null, UiText(R.string.dexcom_reading_reading), null))
        is LatestReadingPresentation.Reading -> {
            val current = presentation.freshnessAt(nowEpochMs) == ReadingFreshness.CURRENT
            // Whole minutes, rounded down. Before the measurement instant no age is stated as negative.
            val minutes = maxOf(0L, nowEpochMs - presentation.measuredAtEpochMs) / 60_000L
            val measured = UiText(R.string.dexcom_reading_measured, listOf(minutes, time(presentation.measuredAtEpochMs, zone)))
            DexcomReadingView(
                UiText(R.string.dexcom_reading_value, listOf(presentation.valueMgDl)),
                listOf(
                    measured,
                    UiText(if (current) R.string.dexcom_reading_current else R.string.dexcom_reading_expired),
                    UiText(R.string.dexcom_reading_withheld_received, listOf(time(presentation.receivedAtEpochMs, zone))),
                    provenance(presentation.provenance),
                    UiText(R.string.dexcom_reading_status),
                    UiText(R.string.dexcom_reading_details, listOf(presentation.transmitterShort, presentation.trendLiteral)),
                ),
                if (current) {
                    CurrentGlucoseView(UiText(R.string.dexcom_reading_value, listOf(presentation.valueMgDl)),
                        UiText(R.string.glucose_current_source), measured)
                } else {
                    CurrentGlucoseView(null, UiText(R.string.glucose_status_expired), measured)
                },
            )
        }
        is LatestReadingPresentation.Withheld -> DexcomReadingView(null, listOf(
            UiText(when (presentation.cause) {
                WithheldCause.OUTSIDE_PRODUCER_INTERVAL -> R.string.dexcom_reading_withheld_outside
                WithheldCause.CONFLICT -> R.string.dexcom_reading_withheld_conflict
                WithheldCause.RECEPTION_AFTER_NOW -> R.string.dexcom_reading_withheld_clock
                WithheldCause.FUTURE_TIMESTAMP -> R.string.dexcom_reading_withheld_future
                WithheldCause.INVALID_TIMESTAMP -> R.string.dexcom_reading_withheld_invalid_time
            }),
            UiText(R.string.dexcom_reading_withheld_received, listOf(time(presentation.receivedAtEpochMs, zone))),
            provenance(presentation.provenance),
            UiText(R.string.dexcom_reading_code, listOf(presentation.cause.code)),
        ), CurrentGlucoseView(null, UiText(when (presentation.cause) {
            WithheldCause.OUTSIDE_PRODUCER_INTERVAL -> R.string.glucose_status_invalid
            WithheldCause.CONFLICT -> R.string.glucose_status_conflicting
            WithheldCause.RECEPTION_AFTER_NOW, WithheldCause.FUTURE_TIMESTAMP, WithheldCause.INVALID_TIMESTAMP ->
                R.string.glucose_status_clock_anomaly
        }), null))
        is LatestReadingPresentation.NoReading -> DexcomReadingView(null, buildList {
            add(UiText(when (presentation.cause) {
                NoReadingCause.RECEIVER_DISABLED -> R.string.dexcom_reading_receiver_disabled
                NoReadingCause.NEXT_PERMISSION_MISSING -> R.string.dexcom_reading_next_permission_missing
                NoReadingCause.POLICY_PENDING -> R.string.dexcom_reading_policy_pending
                NoReadingCause.DURABLE_BLOCK -> R.string.dexcom_reading_durable_block
                NoReadingCause.NO_READING_RECORDED -> R.string.dexcom_reading_no_reading
                NoReadingCause.LOG_UNREADABLE -> R.string.dexcom_reading_log_unreadable
                NoReadingCause.LAST_MESSAGE_REJECTED -> R.string.dexcom_reading_last_rejected
                NoReadingCause.SELECTION_PENDING_B1 -> R.string.dexcom_reading_selection_pending
            }))
            add(UiText(R.string.dexcom_reading_code, listOf(presentation.cause.code)))
            presentation.detail?.let { add(UiText(R.string.dexcom_reading_code, listOf(it))) }
        }, CurrentGlucoseView(null, UiText(when (presentation.cause) {
            NoReadingCause.NO_READING_RECORDED -> R.string.glucose_status_missing
            NoReadingCause.NEXT_PERMISSION_MISSING -> R.string.glucose_status_permission_denied
            NoReadingCause.RECEIVER_DISABLED, NoReadingCause.POLICY_PENDING, NoReadingCause.DURABLE_BLOCK,
            NoReadingCause.LOG_UNREADABLE -> R.string.glucose_status_source_unavailable
            NoReadingCause.LAST_MESSAGE_REJECTED -> R.string.glucose_status_parse_failed
            NoReadingCause.SELECTION_PENDING_B1 -> R.string.glucose_status_unknown
        }), null))
    }

    private fun provenance(provenance: MessageProvenance) = when (provenance) {
        is ApprovedPermissionSet -> UiText(R.string.dexcom_reading_provenance_set)
        is AuthenticatedSender -> UiText(R.string.dexcom_reading_provenance_strict, listOf(provenance.packageName))
    }

    private fun time(epochMs: Long, zone: ZoneId): String = clock.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

/**
 * Read-only access to the latest reading for Inicio and Bolo. Reads run off the main thread with the injected clock;
 * a newer read supersedes an older one. It never writes or keeps state beyond the activity.
 */
internal class DexcomReadingModel(
    private val reader: (Long) -> LatestReadingPresentation,
    private val clock: () -> Long,
) : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private var token = 0

    /** Null while the first read runs. A new read keeps showing nothing older than its own result once it lands. */
    var state: LatestReadingPresentation? = null
        private set
    var changed: (() -> Unit)? = null

    /** Entry and resume: nothing earlier stays on screen while the read runs. */
    fun load() = read(clearFirst = true)

    /**
     * Periodic re-read while the screen stays open, so a new reading appears and an old one stops being shown as
     * current without leaving the screen. The previous state stays until the new one lands; [changed] is always
     * invoked, because the age and the freshness shown depend on the instant even when the reading is the same.
     */
    fun refresh() = read(clearFirst = false)

    private fun read(clearFirst: Boolean) {
        if (disposed.get()) return
        val current = ++token
        if (clearFirst) state = null
        worker.execute {
            val next = try {
                reader(clock())
            } catch (_: Exception) {
                LatestReadingPresentation.NoReading(NoReadingCause.LOG_UNREADABLE, "dexcom_presentation.read_failed")
            }
            main.post {
                if (disposed.get() || current != token) return@post
                state = next
                changed?.invoke()
            }
        }
    }

    override fun onCleared() {
        disposed.set(true)
        worker.shutdown()
    }

    companion object {
        /** How often an open screen re-reads the log. Technical constant, not a clinical parameter. */
        const val REFRESH_INTERVAL_MS: Long = 30_000L
    }
}
